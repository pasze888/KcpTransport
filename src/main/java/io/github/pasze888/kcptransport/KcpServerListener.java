package io.github.pasze888.kcptransport;

import io.github.pasze888.kcptransport.kcp.UkcpChannelOption;
import io.github.pasze888.kcptransport.kcp.UkcpServerChannel;
import io.github.pasze888.kcptransport.mixin.ServerConnectionListenerAccess;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.jspecify.annotations.Nullable;

/**
 * Brings up a KCP (UDP) listener next to the vanilla TCP one.
 *
 * <p>Everything above the byte-stream channel is reused verbatim: the child pipeline is wired by
 * {@link ConnectionPipeline}, the same module the client path and the smoke test use. The only
 * server-specific part is the handshake listener attached afterwards. Conversation routing by
 * conv is handled by {@link UkcpServerChannel} itself, so there is no per-connection bookkeeping
 * here.
 */
public final class KcpServerListener {

    /**
     * Private groups rather than the one MC hands out: KCP's lifetime must not be tied to a
     * server or a world.
     *
     * <p>Daemon threads matter here: the group is static and never shut down, and non-daemon
     * Netty threads would keep the JVM alive after a server stop that does not force System.exit.
     *
     * <p>The parent has one loop for UDP reads and the child group supplies session loops. A session
     * is never driven by the parent's loop; its kernel, pipeline, and scheduled flushes stay on the
     * loop selected when the child is registered (ADR-0008 / issue #17).
     */
    private static final EventLoopGroup KCP_BOSS_GROUP = new MultiThreadIoEventLoopGroup(
            1, new DefaultThreadFactory("kcptransport-boss", true), NioIoHandler.newFactory());

    // ponytail: two session loops initially; size this fixed pool from load measurements later.
    private static final EventLoopGroup KCP_GROUP = new MultiThreadIoEventLoopGroup(
            2, new DefaultThreadFactory("kcptransport-session", true), NioIoHandler.newFactory());

    private KcpServerListener() {
    }

    /**
     * Binds the KCP listener on the same port number as the TCP one (UDP is a separate
     * protocol, so the numbers do not collide). Called after the TCP listener is up; a failure
     * here is logged and swallowed, because vanilla TCP service must survive KCP not working.
     */
    public static void start(ServerConnectionListener self, @Nullable InetAddress address, int port) {
        ServerConnectionListenerAccess access = (ServerConnectionListenerAccess) self;
        MinecraftServer server = access.kcpTransport$server();
        List<ChannelFuture> channels = access.kcpTransport$channels();
        Queue<Connection> pendingConnections = access.kcpTransport$pendingConnections();

        if (address == null) {
            address = new InetSocketAddress(port).getAddress();
        }

        synchronized (channels) {
            try {
                // The bound ChannelFuture joins self.channels, so ServerConnectionListener#stop
                // (shutdown) and stopTcpServerListener (LAN off, filters non-local channels)
                // already close the KCP listener -- only the event loop above outlives them.
                ChannelFuture future = new ServerBootstrap()
                        .group(KCP_BOSS_GROUP, KCP_GROUP)
                        .channel(UkcpServerChannel.class)
                        .childOption(UkcpChannelOption.UKCP_NODELAY, true)
                        .childOption(UkcpChannelOption.UKCP_INTERVAL, 10)
                        .childOption(UkcpChannelOption.UKCP_FAST_RESEND, 2)
                        .childOption(UkcpChannelOption.UKCP_NOCWND, true)
                        .childOption(UkcpChannelOption.UKCP_MTU, 1400)
                        // The windows are spelled out rather than left to the vendored defaults
                        // (java-kcp ships 32/32) so the base swap did not change throughput
                        // behaviour. Aligning them with the client's 1024 is a separate knob with
                        // its own ticket.
                        .childOption(UkcpChannelOption.UKCP_SND_WND, 32)
                        .childOption(UkcpChannelOption.UKCP_RCV_WND, 128)
                        .childHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel channel) {
                                KcpServerListener.initChannel(channel, server, pendingConnections);
                            }
                        })
                        .localAddress(address, port)
                        .bind()
                        .syncUninterruptibly();
                channels.add(future);
                KcpTransport.LOGGER.info("KCP transport listening on udp/{}:{}", address.getHostAddress(), port);
            } catch (Exception e) {
                KcpTransport.LOGGER.warn(
                        "Could not bind the KCP listener on udp/{}:{}; TCP keeps serving as usual",
                        address.getHostAddress(), port, e);
            }
        }
    }

    private static void initChannel(Channel channel, MinecraftServer server, Queue<Connection> pendingConnections) {
        // Vanilla stack, wired by the same module the client path and the smoke test use.
        // Only the listener differs between the two sides.
        Connection connection = ConnectionPipeline.configure(
                channel.pipeline(), PacketFlow.SERVERBOUND, server, pendingConnections);
        connection.setListenerForServerboundHandshake(new ServerHandshakePacketListenerImpl(server, connection));
    }
}
