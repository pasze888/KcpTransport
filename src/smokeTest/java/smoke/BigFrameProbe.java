package smoke;

import io.github.pasze888.kcptransport.ConnectionPipeline;
import io.github.pasze888.kcptransport.kcp.UkcpChannel;
import io.github.pasze888.kcptransport.kcp.UkcpChannelOption;
import io.github.pasze888.kcptransport.kcp.UkcpClientChannel;
import io.github.pasze888.kcptransport.kcp.UkcpServerChannel;
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * On-demand diagnostic for large KCP frames: where does the transfer stall, and what kills
 * the channel? Prints bytes written, send queue left, peer ack point and transmission counters.
 *
 * <p>Deliberately NOT wired into {@code check}: past the kernel's segment cap it closes channels
 * on purpose, so it must never gate CI. The cap is 255 segments on the vendored base (the old
 * kcp-netty base refused at 127); {@code smokeKernel} pins the current boundary. Run it with the
 * smoke classpath on JDK 25:
 *
 * <pre>
 * java -cp "&lt;smoke runtime classpath&gt;" \
 *      -Dprobe.sizes=100000,300000,350880,350881 -Dprobe.prime=true -Dprobe.waitMs=5000 \
 *      smoke.BigFrameProbe
 * </pre>
 *
 * <p>Findings live in the commit message and issue #3.
 */
public final class BigFrameProbe {

    private static final int MTU = 1400;
    private static final int MSS = MTU - 24;

    public static void main(String[] args) throws Exception {
        String[] sizesArg = System.getProperty("probe.sizes", "100000").split(",");
        boolean prime = Boolean.getBoolean("probe.prime");
        long waitMs = Long.getLong("probe.waitMs", 3000L);
        System.out.println("[probe] MSS=" + MSS + " capAt255segs=" + (255 * MSS) + " prime=" + prime);

        // KcpTransport: one loop, like the production listener. The kernel has no locks and keeps
        // reusable iterators, so a session must be driven by one thread only (issue #17 is the
        // ticket that lifts this with per-session affinity).
        EventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel server = null;
        try {
            server = startServer(group);
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            System.out.println("[probe] server udp port " + port);
            for (String s : sizesArg) {
                runOne(group, port, Integer.parseInt(s.trim()), prime, waitMs);
            }
        } finally {
            if (server != null) server.close().awaitUninterruptibly(2000);
            group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).awaitUninterruptibly(2000);
            System.out.println("[probe] done");
            System.out.flush();
        }
    }

    private static Channel startServer(EventLoopGroup group) throws Exception {
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(group).channel(UkcpServerChannel.class)
          .childOption(UkcpChannelOption.UKCP_NODELAY, true)
          .childOption(UkcpChannelOption.UKCP_INTERVAL, 10)
          .childOption(UkcpChannelOption.UKCP_FAST_RESEND, 2)
          .childOption(UkcpChannelOption.UKCP_NOCWND, true)
          .childOption(UkcpChannelOption.UKCP_MTU, MTU)
          .childOption(UkcpChannelOption.UKCP_SND_WND, 1024)
          .childOption(UkcpChannelOption.UKCP_RCV_WND, 1024)
          .childHandler(new ChannelInitializer<UkcpChannel>() {
              @Override protected void initChannel(UkcpChannel ch) {
                  ch.pipeline().addLast("watch", watcher("server-child"));
                  ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.SERVERBOUND);
                  ch.pipeline()
                    .addLast("echo", new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            ctx.writeAndFlush(msg);
                        }
                    });
              }
          });
        return sb.bind("127.0.0.1", 0).sync().channel();
    }

    private static ChannelInboundHandlerAdapter watcher(final String who) {
        return new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                System.out.println("[probe]   " + who + " EXC " + cause);
                ctx.fireExceptionCaught(cause);
            }
            @Override public void channelInactive(ChannelHandlerContext ctx) {
                System.out.println("[probe]   " + who + " INACTIVE");
                ctx.fireChannelInactive();
            }
        };
    }

    private static void runOne(EventLoopGroup group, int port, int size, boolean prime, long waitMs)
            throws Exception {
        BlockingQueue<ByteBuf> inbox = new LinkedBlockingQueue<>();
        Bootstrap cb = new Bootstrap();
        cb.group(group).channel(UkcpClientChannel.class)
          .option(UkcpChannelOption.UKCP_NODELAY, true)
          .option(UkcpChannelOption.UKCP_INTERVAL, 10)
          .option(UkcpChannelOption.UKCP_FAST_RESEND, 2)
          .option(UkcpChannelOption.UKCP_NOCWND, true)
          .option(UkcpChannelOption.UKCP_MTU, MTU)
          .option(UkcpChannelOption.UKCP_SND_WND, 1024)
          .option(UkcpChannelOption.UKCP_RCV_WND, 1024)
          .handler(new ChannelInitializer<UkcpChannel>() {
              @Override protected void initChannel(UkcpChannel ch) {
                  ch.pipeline().addLast("watch", watcher("client"));
                  ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.CLIENTBOUND);
                  ch.pipeline()
                    .addLast("collect", new ChannelInboundHandlerAdapter() {
                        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
                            inbox.add((ByteBuf) msg);
                        }
                    });
              }
          });
        Channel client = cb.connect("127.0.0.1", port).sync().channel();
        try {
            if (prime) {
                ByteBuf warm = Unpooled.wrappedBuffer("warmup".getBytes());
                client.writeAndFlush(warm).awaitUninterruptibly(2000);
                ByteBuf got = inbox.poll(2000, TimeUnit.MILLISECONDS);
                System.out.println("[probe]   warmup echo=" + (got != null));
                if (got != null) got.release();
            }

            byte[] payload = new byte[size];
            for (int i = 0; i < size; i++) payload[i] = (byte) (i * 31 + 7);
            int segs = (size + 3 + MSS - 1) / MSS;

            long t0 = System.nanoTime();
            boolean writeDone = client.writeAndFlush(Unpooled.wrappedBuffer(payload))
                    .awaitUninterruptibly(waitMs);
            System.out.println("[probe] size=" + size + " segs=" + segs
                    + " writeSettled=" + writeDone + " after=" + ms(t0) + "ms"
                    + " | " + snapshot(client));

            ByteBuf got = inbox.poll(waitMs, TimeUnit.MILLISECONDS);
            System.out.println("[probe]   echoed=" + (got != null)
                    + " elapsed=" + ms(t0) + "ms active=" + client.isActive()
                    + " | " + snapshot(client)
                    + (got != null ? " echoedBytes=" + got.readableBytes() : ""));
            if (got != null) got.release();
        } finally {
            client.close().awaitUninterruptibly(1000);
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private static Object kcpOf(Channel ch) {
        try {
            Field uf = ch.getClass().getDeclaredField("ukcp");
            uf.setAccessible(true);
            Object ukcp = uf.get(ch);
            if (ukcp == null) {
                // The session is built on first use, so a channel that never wrote has none yet.
                return null;
            }
            Field kf = ukcp.getClass().getDeclaredField("kcp");
            kf.setAccessible(true);
            return kf.get(ukcp);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static String snapshot(Channel ch) {
        Object kcp = kcpOf(ch);
        if (kcp == null) return "(no kcp)";
        try {
            Method waitSnd = kcp.getClass().getMethod("waitSnd");
            return String.format("waitSnd=%s sndUna=%d sndNxt=%d rcvNxt=%d",
                    waitSnd.invoke(kcp), counter(kcp, "sndUna"), counter(kcp, "sndNxt"),
                    counter(kcp, "rcvNxt"));
        } catch (Exception e) {
            return "(metrics failed: " + e + ")";
        }
    }

    /** Reads one of the vendored kernel's private counters; it exposes no getters for these. */
    private static long counter(Object kcp, String name) throws ReflectiveOperationException {
        Field f = kcp.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return ((Number) f.get(kcp)).longValue();
    }
}
