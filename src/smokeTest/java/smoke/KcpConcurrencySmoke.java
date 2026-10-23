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
import io.netty.util.concurrent.MultithreadEventExecutorGroup;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Pins the concurrency contract issue #10 was about.
 *
 * <p>Two separate things are checked, because they fail in different ways:
 *
 * <ol>
 *   <li><b>The server event loop must stay single-threaded.</b> kcp-netty keeps every child's
 *       {@code Kcp} state on the server channel's event loop and holds no locks anywhere, so a
 *       multi-threaded group lets two threads mutate one state machine and connections die with
 *       {@code ConcurrentModificationException} during configuration. Upstream's own echo example
 *       uses a multi-threaded group, so copying it is not a defence.</li>
 *   <li><b>Several live connections multiplex correctly.</b> Sessions share one UDP socket, so a
 *       demux bug shows up as cross-talk or a lost reply rather than an exception.</li>
 * </ol>
 */
public final class KcpConcurrencySmoke {

    private static final int CLIENTS = 3;
    private static final int ROUNDS = 10;

    public static void main(String[] args) throws Exception {
        assertServerEventLoopIsSingleThreaded();
        assertConcurrentConnectionsStayIsolated();
        System.out.println("[concurrency] PASS: single-threaded event loop invariant holds and "
                + CLIENTS + " concurrent connections stay isolated");
    }

    /**
     * Reads the production group rather than building one here: the point is to catch somebody
     * raising the thread count in {@code KcpServerListener}, which no behaviour test can.
     */
    private static void assertServerEventLoopIsSingleThreaded() throws Exception {
        Class<?> listener = Class.forName("io.github.pasze888.kcptransport.KcpServerListener");
        Field group = listener.getDeclaredField("KCP_GROUP");
        group.setAccessible(true);
        int threads = ((MultithreadEventExecutorGroup) group.get(null)).executorCount();
        if (threads != 1) {
            throw new AssertionError("FAIL: the KCP server group must stay single-threaded, found "
                    + threads + " threads; kcp-netty has no locks and a multi-threaded group races "
                    + "the same Kcp state (issue #10)");
        }
        System.out.println("[concurrency] server KCP group threads = " + threads);
    }

    private static void assertConcurrentConnectionsStayIsolated() throws Exception {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup worker = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        Channel server = null;
        Channel[] clients = new Channel[CLIENTS];
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.group(boss, worker)
              .channel(UkcpServerChannel.class)
              .childOption(UkcpChannelOption.UKCP_MTU, 1400)
              .childHandler(new ChannelInitializer<UkcpChannel>() {
                  @Override
                  protected void initChannel(UkcpChannel ch) {
                      ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.SERVERBOUND);
                      ch.pipeline().addLast("echo", new ChannelInboundHandlerAdapter() {
                          @Override
                          public void channelRead(ChannelHandlerContext ctx, Object msg) {
                              ctx.writeAndFlush(msg);
                          }
                      });
                  }
              });
            server = sb.bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            System.out.println("[concurrency] server bound on UDP port " + port);

            @SuppressWarnings("unchecked")
            BlockingQueue<ByteBuf>[] inboxes = new BlockingQueue[CLIENTS];
            for (int i = 0; i < CLIENTS; i++) {
                inboxes[i] = new LinkedBlockingQueue<>();
                clients[i] = connect(worker, port, inboxes[i]);
            }

            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < CLIENTS; i++) {
                    clients[i].writeAndFlush(frame("c" + i + "-r" + round));
                }
                for (int i = 0; i < CLIENTS; i++) {
                    String expected = "c" + i + "-r" + round;
                    ByteBuf got = inboxes[i].poll(5, TimeUnit.SECONDS);
                    if (got == null) {
                        throw new AssertionError("FAIL: client " + i + " got no echo for " + expected);
                    }
                    String actual = got.toString(StandardCharsets.UTF_8);
                    got.release();
                    if (!expected.equals(actual)) {
                        throw new AssertionError("FAIL: client " + i + " expected " + expected
                                + " but got " + actual + " (cross-talk between sessions)");
                    }
                }
            }
            System.out.println("[concurrency] " + CLIENTS + " clients x " + ROUNDS
                    + " rounds echoed without cross-talk");
        } finally {
            for (Channel c : clients) {
                if (c != null) {
                    c.close().syncUninterruptibly();
                }
            }
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }

    private static ByteBuf frame(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    private static Channel connect(EventLoopGroup worker, int port, BlockingQueue<ByteBuf> inbox)
            throws Exception {
        Bootstrap cb = new Bootstrap();
        cb.group(worker)
          .channel(UkcpClientChannel.class)
          .option(UkcpChannelOption.UKCP_MTU, 1400)
          .handler(new ChannelInitializer<UkcpChannel>() {
              @Override
              protected void initChannel(UkcpChannel ch) {
                  ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.CLIENTBOUND);
                  ch.pipeline().addLast("collect", new ChannelInboundHandlerAdapter() {
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
