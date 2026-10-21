package io.github.pasze888.kcptransport;

import io.github.pasze888.kcptransport.kcp.UkcpChannelOption;
import io.github.pasze888.kcptransport.kcp.UkcpServerChannel;
import io.github.pasze888.kcptransport.mixin.ServerConnectionListenerAccess;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Queue;
import net.minecraft.network.Connection;
import net.minecraft.network.RateKickingConnection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import org.jspecify.annotations.Nullable;

/**
 * Brings up a KCP (UDP) listener next to the vanilla TCP one.
 *
 * <p>Everything above the byte-stream channel is reused verbatim: the child pipeline is built
 * with the same {@link Connection#configureSerialization} call the TCP bootstrap uses, which
 * the smoke test proves works unmodified on a KCP channel. Conversation routing by conv is
 * handled by {@link UkcpServerChannel} itself, so there is no per-connection bookkeeping here.
 */
public final class KcpServerListener {

    /** Same knob as vanilla: -Dneoforge.readTimeout, default 30 seconds. */
    private static final int READ_TIMEOUT = Integer.getInteger("neoforge.readTimeout", 30);

    /**
     * kcp-netty channels require a {@code NioEventLoop} (their isCompatible check), which MC's
     * EventLoopGroupHolder does not provide: Netty 4.2 turns MultiThreadIoEventLoopGroup into
     * SingleThreadIoEventLoop, and registering into it fails with "incompatible event loop type".
     * So KCP runs on its own NIO group instead of reusing the vanilla one. Daemon threads
     * matter here: the group is static and never shut down, and non-daemon Netty threads
     * would keep the JVM alive after a server stop that does not force System.exit.
     */
    private static final EventLoopGroup KCP_GROUP =
            new NioEventLoopGroup(0, new DefaultThreadFactory("kcptransport-IO", true));

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
                        .group(KCP_GROUP)
                        .channel(UkcpServerChannel.class)
                        .childOption(UkcpChannelOption.UKCP_NODELAY, true)
                        .childOption(UkcpChannelOption.UKCP_INTERVAL, 10)
                        .childOption(UkcpChannelOption.UKCP_FAST_RESEND, 2)
                        .childOption(UkcpChannelOption.UKCP_NOCWND, true)
                        .childOption(UkcpChannelOption.UKCP_MTU, 1400)
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
        ChannelPipeline pipeline = channel.pipeline().addLast("timeout", new ReadTimeoutHandler(READ_TIMEOUT));
        // The vanilla serialization stack, byte for byte, with no adapter in front of it.
        Connection.configureSerialization(pipeline, PacketFlow.SERVERBOUND, false, null);
        int rateLimitPacketsPerSecond = server.getRateLimitPacketsPerSecond();
        Connection connection = rateLimitPacketsPerSecond > 0
                ? new RateKickingConnection(rateLimitPacketsPerSecond)
                : new Connection(PacketFlow.SERVERBOUND);
        // Same hand-off queue the TCP bootstrap feeds; ServerConnectionListener.tick() adopts it.
        pendingConnections.add(connection);
        connection.configurePacketHandler(pipeline);
        connection.setListenerForServerboundHandshake(new ServerHandshakePacketListenerImpl(server, connection));
    }
}
