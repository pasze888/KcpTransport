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
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.protocol.PacketFlow;

/** Real UDP sessions must have loop affinity and must not cross-talk on the shared socket. */
public final class KcpConcurrencySmoke {

    private static final int CLIENTS = 3;
    private static final int ROUNDS = 10;
    private static final int SESSION_LOOPS = 2;

    public static void main(String[] args) throws Exception {
        // Negative control uses the real channel fixture, not synthetic loop identities.
        try {
            assertConcurrentConnectionsStayIsolated(1);
            throw new AssertionError("FAIL: shared-loop negative control was not detected");
        } catch (AssertionError expected) {
            if (!"FAIL: concurrent sessions share an event loop".equals(expected.getMessage())) {
                throw expected;
            }
            System.out.println("[concurrency] shared-loop negative control rejected");
        }
        assertConcurrentConnectionsStayIsolated(SESSION_LOOPS);
        System.out.println("[concurrency] PASS: per-session loop affinity and "
                + CLIENTS + " concurrent connections stay isolated");
    }

    private static void assertConcurrentConnectionsStayIsolated(int workerCount) throws Exception {
        EventLoopGroup boss = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        EventLoopGroup workers = new MultiThreadIoEventLoopGroup(workerCount, NioIoHandler.newFactory());
        EventLoopGroup clientsGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        BlockingQueue<Channel> sessions = new LinkedBlockingQueue<>();
        BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();
        Channel server = null;
        Channel[] clients = new Channel[CLIENTS];
        @SuppressWarnings("unchecked")
        BlockingQueue<ByteBuf>[] inboxes = new BlockingQueue[CLIENTS];
        try {
            ServerBootstrap sb = new ServerBootstrap();
            sb.group(boss, workers)
              .channel(UkcpServerChannel.class)
              .childOption(UkcpChannelOption.UKCP_MTU, 1400)
              .childHandler(new ChannelInitializer<UkcpChannel>() {
                  @Override
                  protected void initChannel(UkcpChannel ch) {
                      sessions.add(ch);
                      ConnectionPipeline.configureFraming(ch.pipeline(), PacketFlow.SERVERBOUND);
                      ch.pipeline().addLast("echo", new ChannelInboundHandlerAdapter() {
                          @Override
                          public void channelRead(ChannelHandlerContext ctx, Object msg) {
                              if (!ctx.channel().eventLoop().inEventLoop()) {
                                  failures.add(new AssertionError("FAIL: session callback ran outside its event loop"));
                              }
                              ctx.writeAndFlush(msg);
                          }

                          @Override
                          public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                              failures.add(cause);
                              ctx.close();
                          }
                      });
                  }
              });
            server = sb.bind("127.0.0.1", 0).sync().channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();

            for (int i = 0; i < CLIENTS; i++) {
                inboxes[i] = new LinkedBlockingQueue<>();
                clients[i] = connect(clientsGroup, port, inboxes[i]);
            }

            Channel[] accepted = new Channel[CLIENTS];
            for (int round = 0; round < ROUNDS; round++) {
                for (int i = 0; i < CLIENTS; i++) {
                    clients[i].writeAndFlush(frame("c" + i + "-r" + round)).sync();
                }
                if (round == 0) {
                    for (int i = 0; i < CLIENTS; i++) {
                        accepted[i] = sessions.poll(5, TimeUnit.SECONDS);
                        if (accepted[i] == null) {
                            throw new AssertionError("FAIL: session was not registered");
                        }
                    }
                    if (java.util.Arrays.stream(accepted).map(Channel::eventLoop).distinct().count() < 2) {
                        throw new AssertionError("FAIL: concurrent sessions share an event loop");
                    }
                }
                for (int i = 0; i < CLIENTS; i++) {
                    String expected = "c" + i + "-r" + round;
                    ByteBuf got = inboxes[i].poll(5, TimeUnit.SECONDS);
                    if (got == null) {
                        throw new AssertionError("FAIL: client " + i + " got no echo for " + expected
                                + "; session failure=" + failures.peek());
                    }
                    String actual = got.toString(StandardCharsets.UTF_8);
                    got.release();
                    if (!expected.equals(actual)) {
                        throw new AssertionError("FAIL: client " + i + " expected " + expected
                                + " but got " + actual + " (cross-talk between sessions)");
                    }
                }
                if (!failures.isEmpty()) {
                    throw new AssertionError("FAIL: session affinity or pipeline failure", failures.peek());
                }
            }
            // Parent shutdown must close its children on their loops too.
            server.close().sync();
            for (Channel child : accepted) {
                if (!child.closeFuture().await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("FAIL: parent shutdown did not close a session");
                }
            }
            if (!failures.isEmpty()) {
                throw new AssertionError("FAIL: session shutdown failure", failures.peek());
            }
            System.out.println("[concurrency] " + CLIENTS + " clients x " + ROUNDS
                    + " rounds echoed across " + workerCount + " session loops without cross-talk");
        } finally {
            for (Channel c : clients) {
                if (c != null) {
                    c.close().syncUninterruptibly();
                }
            }
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            workers.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            clientsGroup.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
            for (BlockingQueue<ByteBuf> inbox : inboxes) {
                if (inbox != null) {
                    Object msg;
                    while ((msg = inbox.poll()) != null) {
                        ReferenceCountUtil.release(msg);
                    }
                }
            }
        }
    }

    private static ByteBuf frame(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
    }

    private static Channel connect(EventLoopGroup group, int port, BlockingQueue<ByteBuf> inbox)
            throws Exception {
        Bootstrap cb = new Bootstrap();
        cb.group(group)
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
