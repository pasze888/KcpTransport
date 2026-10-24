package smoke;

import io.github.pasze888.kcptransport.ConnectionPipeline;
import io.github.pasze888.kcptransport.kcp.InlineMessageExecutor;
import io.github.pasze888.kcptransport.kcp.UkcpChannel;
import io.github.pasze888.kcptransport.kcp.UkcpChannelOption;
import io.github.pasze888.kcptransport.kcp.UkcpClientChannel;
import io.github.pasze888.kcptransport.kcp.UkcpServerChannel;
import io.github.pasze888.kcptransport.kcp.UkcpSessionConfig;
import io.github.pasze888.kcptransport.kcp.base.IChannelManager;
import io.github.pasze888.kcptransport.kcp.base.KcpConfig;
import io.github.pasze888.kcptransport.kcp.base.KcpListener;
import io.github.pasze888.kcptransport.kcp.base.Ukcp;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import net.minecraft.network.protocol.PacketFlow;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Smoke test for the vendored KCP transport on the Netty version Minecraft ships.
 *
 * <p>Guards the things that are easy to get wrong here:
 * <ul>
 *   <li>the channel round-trips a packet on Netty 4.2 (upstream is built for 4.1);</li>
 *   <li>each client gets its own non-zero conv and two clients do not cross-talk;</li>
 *   <li>garbage UDP aimed at the port does not tear down live sessions
 *       (upstream issue #15: malformed input used to raise IOException and close);</li>
 *   <li>Minecraft's own framing handlers work directly on top of the KCP channel, so no
 *       stream adapter is needed between them, including payloads large enough that KCP has
 *       to fragment them across many segments.</li>
 * </ul>
 */
public final class KcpSmoke {

    /** Stands in for the server's session table in the bare-session check below. */
    private static final IChannelManager NO_CHANNELS = new IChannelManager() {
        @Override
        public Ukcp get(io.netty.channel.socket.DatagramPacket msg) {
            return null;
        }

        @Override
        public void add(SocketAddress socketAddress, Ukcp ukcp, io.netty.channel.socket.DatagramPacket msg) {
        }

        @Override
        public void del(Ukcp ukcp) {
        }

        @Override
        public Collection<Ukcp> getAll() {
            return Collections.emptyList();
        }
    };

    public static void main(String[] args) throws Exception {
        String nettyVersion = Channel.class.getPackage().getImplementationVersion();
        System.out.println("[smoke] netty on classpath = " + nettyVersion);
        if (nettyVersion == null || !nettyVersion.startsWith("4.2")) {
            throw new AssertionError("expected Netty 4.2.x, got " + nettyVersion);
        }

        malformedInputIsDropped();

        // KcpTransport: one loop for the whole KCP endpoint, exactly like KcpServerListener
        // (.group(KCP_GROUP) with a single thread). A session's kernel keeps reusable iterators and
        // no locks, so the parent channel and every child must share one thread; splitting boss from
        // worker here would let two threads drive one session (Kcp.release()/flush() then trip over
        // each other). Issue #17 is where sessions get loops of their own, with the affinity that
        // needs.
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel server = null;
        Channel a = null;
        Channel b = null;
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.group(group)
              .channel(UkcpServerChannel.class)
              .childOption(UkcpChannelOption.UKCP_NODELAY, true)
              .childOption(UkcpChannelOption.UKCP_INTERVAL, 10)
              .childOption(UkcpChannelOption.UKCP_FAST_RESEND, 2)
              .childOption(UkcpChannelOption.UKCP_NOCWND, true)
              .childOption(UkcpChannelOption.UKCP_MTU, 1400)
              .childOption(UkcpChannelOption.UKCP_SND_WND, 1024)
              .childOption(UkcpChannelOption.UKCP_RCV_WND, 1024)
              // Minecraft's real pipeline, straight onto the KCP channel, nothing in between.
              .childHandler(new ChannelInitializer<UkcpChannel>() {
                  @Override
                  protected void initChannel(UkcpChannel ch) {
                      // The same framing module the server listener uses, not a hand-rolled copy.
                      ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.SERVERBOUND);
                      ch.pipeline()
                        .addLast("echo", new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                ctx.writeAndFlush(msg);
                            }
                        });
                  }
              });

            server = sb.bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            System.out.println("[smoke] KCP server bound on UDP port " + port);

            BlockingQueue<ByteBuf> inA = new LinkedBlockingQueue<>();
            BlockingQueue<ByteBuf> inB = new LinkedBlockingQueue<>();

            a = connect(group, port, inA);
            b = connect(group, port, inB);

            int convA = ((UkcpChannel) a).conv();
            int convB = ((UkcpChannel) b).conv();
            System.out.println("[smoke] convA=" + convA + " convB=" + convB);

            if (convA == 0 || convB == 0) {
                throw new AssertionError("FAIL: conv must be non-zero, got " + convA + "/" + convB);
            }
            if (convA == convB) {
                throw new AssertionError("FAIL: two clients share conv " + convA);
            }

            echo(a, inA, "hello-from-A");
            echo(b, inB, "hello-from-B");

            // Malformed datagrams from a foreign source address, the way a port scan,
            // a monitoring probe or a load-balancer health check would arrive.
            injectGarbage(port);

            if (!a.isActive() || !b.isActive()) {
                throw new AssertionError("FAIL: a live session died from garbage input");
            }
            echo(a, inA, "still-alive-A");
            echo(b, inB, "still-alive-B");

            // A payload far above the MTU: KCP fragments it across segments and must deliver
            // it as exactly one frame, so Varint21FrameDecoder sees clean message boundaries.
            byte[] big = new byte[100_000];
            for (int i = 0; i < big.length; i++) {
                big[i] = (byte) (i * 31 + 7);
            }
            bigFrame(a, inA, big);
            if (!inB.isEmpty()) {
                throw new AssertionError("FAIL: cross-talk after large frame");
            }

            System.out.println("[smoke] PASS: KCP on Netty " + nettyVersion
                    + ", distinct convs, no cross-talk, garbage input tolerated,"
                    + " vanilla framing works without an adapter");
        } finally {
            if (a != null) a.close().syncUninterruptibly();
            if (b != null) b.close().syncUninterruptibly();
            if (server != null) server.close().syncUninterruptibly();
            group.shutdownGracefully();
            System.out.flush();
        }
    }

    private static void bigFrame(Channel ch, BlockingQueue<ByteBuf> inbox, byte[] payload)
            throws Exception {
        ch.writeAndFlush(Unpooled.wrappedBuffer(payload)).sync();
        ByteBuf got = inbox.poll(15, TimeUnit.SECONDS);
        if (got == null) {
            throw new AssertionError("FAIL: no echo for " + payload.length + " bytes within 15s");
        }
        try {
            if (got.readableBytes() != payload.length) {
                throw new AssertionError("FAIL: expected " + payload.length + " bytes, got "
                        + got.readableBytes());
            }
            byte[] actual = new byte[got.readableBytes()];
            got.getBytes(got.readerIndex(), actual);
            if (!Arrays.equals(payload, actual)) {
                throw new AssertionError("FAIL: large frame corrupted in transit");
            }
            System.out.println("[smoke] large frame intact: " + payload.length
                    + " bytes (KCP fragmented it)");
        } finally {
            got.release();
        }
    }

    /**
     * Feeds non-KCP bytes straight into a session and requires that they are dropped.
     *
     * <p>Regression guard for upstream issue #15. The session layer used to raise IOException for
     * these, and a throw out of its read task closes the session, so a stray datagram aimed at
     * a live peer's address would kill it.
     */
    private static void malformedInputIsDropped() {
        // A bare session, no channel: the drop lives in the session layer (upstream throws there and
        // its read task turns any throw into a close), so a kernel-only check would miss the path
        // that used to kill live sessions.
        boolean[] closed = {false};
        KcpListener quiet = new KcpListener() {
            @Override
            public void onConnected(Ukcp ukcp) {
            }

            @Override
            public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
                throw new AssertionError("FAIL: a malformed datagram was delivered as a message");
            }

            @Override
            public void handleException(Throwable ex, Ukcp ukcp) {
                throw new AssertionError("FAIL: a malformed datagram raised " + ex, ex);
            }

            @Override
            public void handleClose(Ukcp ukcp) {
                closed[0] = true;
            }
        };
        KcpConfig kcpConfig = new KcpConfig();
        kcpConfig.setConv(1234);
        Ukcp ukcp = new Ukcp((data, kcp) -> data.release(), quiet, InlineMessageExecutor.INSTANCE,
                new UkcpSessionConfig(kcpConfig), NO_CHANNELS);

        byte[][] junk = {
                {1, 2, 3},                       // shorter than the KCP header
                new byte[24],                    // header-sized, all zeros: conv 0 is not ours
                new byte[60],                    // plausible size, garbage body
        };
        for (byte[] payload : junk) {
            // read() takes the buffer over (its read task releases it), so nothing to release here.
            ukcp.read(Unpooled.wrappedBuffer(payload));   // must not throw
        }
        if (closed[0] || !ukcp.isActive()) {
            throw new AssertionError("FAIL: a malformed datagram ended the session");
        }
        ukcp.close();
        System.out.println("[smoke] malformed datagrams dropped without throwing or closing");
    }

    private static void injectGarbage(int port) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress loopback = InetAddress.getByName("127.0.0.1");
            byte[] randomLooking = new byte[60];
            Arrays.fill(randomLooking, (byte) 0x7f);
            byte[][] payloads = {
                    "x".getBytes(StandardCharsets.UTF_8),
                    new byte[24],
                    randomLooking,
            };
            for (byte[] payload : payloads) {
                socket.send(new DatagramPacket(payload, payload.length, loopback, port));
            }
            System.out.println("[smoke] injected " + payloads.length + " malformed datagrams");
        }
    }

    /** Writes bytes, expects the same bytes back after vanilla framing on both ends. */
    private static void echo(Channel ch, BlockingQueue<ByteBuf> inbox, String message)
            throws Exception {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        ch.writeAndFlush(Unpooled.wrappedBuffer(payload)).sync();
        ByteBuf got = inbox.poll(5, TimeUnit.SECONDS);
        if (got == null) {
            throw new AssertionError("FAIL: no echo for '" + message + "' within 5s");
        }
        try {
            byte[] actual = new byte[got.readableBytes()];
            got.getBytes(got.readerIndex(), actual);
            String text = new String(actual, StandardCharsets.UTF_8);
            System.out.println("[smoke] echo: " + text);
            if (!message.equals(text)) {
                throw new AssertionError("FAIL: expected '" + message + "' but got '" + text + "'");
            }
        } finally {
            got.release();
        }
    }

    private static Channel connect(EventLoopGroup group, int port, BlockingQueue<ByteBuf> inbox)
            throws Exception {
        Bootstrap cb = new Bootstrap();
        cb.group(group)
          .channel(UkcpClientChannel.class)
          .option(UkcpChannelOption.UKCP_NODELAY, true)
          .option(UkcpChannelOption.UKCP_INTERVAL, 10)
          .option(UkcpChannelOption.UKCP_FAST_RESEND, 2)
          .option(UkcpChannelOption.UKCP_NOCWND, true)
          .option(UkcpChannelOption.UKCP_MTU, 1400)
          .option(UkcpChannelOption.UKCP_SND_WND, 1024)
          .option(UkcpChannelOption.UKCP_RCV_WND, 1024)
          .handler(new ChannelInitializer<UkcpChannel>() {
              @Override
              protected void initChannel(UkcpChannel ch) {
                  ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.CLIENTBOUND);
                  ch.pipeline()
                    .addLast("collect", new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            inbox.add((ByteBuf) msg);
                        }
                    });
              }
          });
        return cb.connect("127.0.0.1", port).sync().channel();
    }
}
