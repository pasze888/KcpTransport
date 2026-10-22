package smoke;

import io.github.pasze888.kcptransport.ConnectionPipeline;
import io.github.pasze888.kcptransport.KcpClientBootstrap;
import io.github.pasze888.kcptransport.kcp.UkcpChannelOption;
import io.github.pasze888.kcptransport.kcp.UkcpServerChannel;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.ServerHandshakePacketListener;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * A real dual-stack endpoint: vanilla TCP and KCP/UDP listening on the <em>same</em> port number,
 * both wired by {@link ConnectionPipeline}, plus the two matching client shapes.
 *
 * <p>Shared by the two end-to-end smokes so that "TCP still works" is measured against the very
 * server the KCP case talks to, not against a second server that could differ. That is what makes
 * the dual-stack independence claim in issue #6 meaningful.
 *
 * <p>Server side, the only thing this fixture supplies is the handshake listener vanilla would
 * supply: decoded {@link ClientIntentionPacket}s land in a queue for the test to inspect, which is
 * how a test observes that the <em>vanilla codec</em> reconstructed the packet. Unlike
 * {@code KcpServerListener}, it passes {@code pending = null}: production hands each Connection to
 * {@code ServerConnectionListener} through that queue, and a smoke has no server to hand it to.
 * The byte-stream-to-vanilla-stack wiring under test is the same either way.
 */
final class DualStackServer implements AutoCloseable {

    /**
     * Arbitrary protocol number, carried through the wire. The codec round-trips it; the fixture
     * never validates it, so its value only has to be stable between the two smokes.
     */
    static final int PROTOCOL = 769;

    private final EventLoopGroup boss = new NioEventLoopGroup(1);
    private final EventLoopGroup worker = new NioEventLoopGroup(1);
    private final BlockingQueue<ClientIntentionPacket> received = new LinkedBlockingQueue<>();
    private Channel tcp;
    private Channel kcp;
    private int port;

    /** Binds TCP on an ephemeral port, then KCP on that same number. */
    static DualStackServer start() throws Exception {
        DualStackServer server = new DualStackServer();
        server.tcp = server.bootstrap(NioServerSocketChannel.class).bind("127.0.0.1", 0).sync().channel();
        server.port = ((InetSocketAddress) server.tcp.localAddress()).getPort();
        server.startKcp();
        return server;
    }

    void startKcp() throws Exception {
        kcp = bootstrap(UkcpServerChannel.class)
                // The same five knobs the production listener sets. With the embedded defaults
                // (100ms interval, nodelay off, no fast resend) a server ACK can land after the
                // client's 100ms initial RTO, so the fixture would be emulating a KCP setup that
                // production never runs -- and a handshake could arrive twice.
                .childOption(UkcpChannelOption.UKCP_NODELAY, true)
                .childOption(UkcpChannelOption.UKCP_INTERVAL, 10)
                .childOption(UkcpChannelOption.UKCP_FAST_RESEND, 2)
                .childOption(UkcpChannelOption.UKCP_NOCWND, true)
                .childOption(UkcpChannelOption.UKCP_MTU, 1400)
                .bind("127.0.0.1", port)
                .sync()
                .channel();
    }

    /**
     * Closes only the UDP side. TCP keeps serving on the same port, which is the shape the failure
     * case needs: same address, one transport gone, the other untouched.
     */
    void stopKcp() {
        if (kcp != null) {
            kcp.close().syncUninterruptibly();
            kcp = null;
        }
    }

    int port() {
        return port;
    }

    /** Waits up to {@code timeoutSeconds} for one decoded handshake; null if none arrived. */
    ClientIntentionPacket awaitIntention(int timeoutSeconds) throws InterruptedException {
        return received.poll(timeoutSeconds, TimeUnit.SECONDS);
    }

    private ServerBootstrap bootstrap(Class<? extends io.netty.channel.ServerChannel> channelType) {
        return new ServerBootstrap()
                .group(boss, worker)
                .channel(channelType)
                .childHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel channel) {
                        // The same module the production server listener and client path use.
                        Connection connection = ConnectionPipeline.configure(
                                channel.pipeline(), PacketFlow.SERVERBOUND, null, null);
                        connection.setListenerForServerboundHandshake(new Capture(received));
                    }
                });
    }

    /** Stands in for ServerHandshakePacketListenerImpl, which needs a live MinecraftServer. */
    private static final class Capture implements ServerHandshakePacketListener {

        private final BlockingQueue<ClientIntentionPacket> sink;

        private Capture(BlockingQueue<ClientIntentionPacket> sink) {
            this.sink = sink;
        }

        @Override
        public void handleIntention(ClientIntentionPacket packet) {
            sink.add(packet);
        }

        @Override
        public void onDisconnect(DisconnectionDetails details) {
            // The smokes assert on what arrived, not on why a connection ended.
        }

        @Override
        public boolean isAcceptingMessages() {
            return true;
        }
    }

    /**
     * Opens a KCP client through the production bootstrap. The Connection is built here and handed
     * over, exactly as ConnectScreen does it.
     */
    static Connection kcpClient(int port) throws InterruptedException {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        KcpClientBootstrap.connect(new InetSocketAddress("127.0.0.1", port), null, connection)
                .syncUninterruptibly();
        awaitChannel(connection);
        return connection;
    }

    /** Opens a TCP client through vanilla's own {@link Connection#connect}, not a Netty stand-in. */
    static Connection tcpClient(int port) throws InterruptedException {
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);
        Connection.connect(new InetSocketAddress("127.0.0.1", port),
                EventLoopGroupHolder.remote(false), connection).syncUninterruptibly();
        awaitChannel(connection);
        return connection;
    }

    /** connect() completes before fireChannelActive() runs on the event loop. */
    static void awaitChannel(Connection connection) throws InterruptedException {
        for (int i = 0; i < 300 && connection.channel() == null; i++) {
            Thread.sleep(10);
        }
        if (connection.channel() == null) {
            throw new AssertionError("FAIL: the connection never got a channel");
        }
    }

    @Override
    public void close() {
        stopKcp();
        if (tcp != null) {
            tcp.close().syncUninterruptibly();
            tcp = null;
        }
        boss.shutdownGracefully();
        worker.shutdownGracefully();
    }
}
