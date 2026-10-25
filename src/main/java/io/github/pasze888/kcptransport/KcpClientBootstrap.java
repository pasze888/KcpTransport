package io.github.pasze888.kcptransport;

import io.github.pasze888.kcptransport.kcp.UkcpChannel;
import io.github.pasze888.kcptransport.kcp.UkcpChannelOption;
import io.github.pasze888.kcptransport.kcp.UkcpClientChannel;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * Opens the KCP (UDP) side of a client connection.
 *
 * <p>Stands in for {@link Connection#connect} when the player opted in with the {@code kcp;}
 * prefix. Everything above the byte-stream channel is the vanilla stack, wired by
 * {@link ConnectionPipeline}, so the two transports differ only underneath.
 *
 * <p>The KCP knobs are all system properties, defaulting to the values the server listener
 * already uses: no config API, no config file, no master switch. The prefix is the switch.
 */
public final class KcpClientBootstrap {

    /**
     * A private group, not the one MC hands in: KCP stays on a single thread (issue #10) and its
     * lifetime is independent of the server. Daemon threads, because this group is static and
     * never shut down. Same reasoning as {@code KcpServerListener}.
     */
    private static final EventLoopGroup KCP_GROUP = new MultiThreadIoEventLoopGroup(
            1, new DefaultThreadFactory("kcptransport-client-IO", true), NioIoHandler.newFactory());

    private static final boolean NODELAY = Boolean.parseBoolean(System.getProperty("kcptransport.nodelay", "true"));
    private static final int INTERVAL = Integer.getInteger("kcptransport.interval", 10);
    private static final int FAST_RESEND = Integer.getInteger("kcptransport.fastResend", 2);
    private static final boolean NOCWND = Boolean.parseBoolean(System.getProperty("kcptransport.nocwnd", "true"));
    private static final int MTU = Integer.getInteger("kcptransport.mtu", 1400);
    private static final int SND_WND = Integer.getInteger("kcptransport.sndWnd", 1024);
    private static final int RCV_WND = Integer.getInteger("kcptransport.rcvWnd", 1024);

    private KcpClientBootstrap() {
    }

    /**
     * Connects {@code address} over KCP and returns the future the caller would have got from
     * vanilla. {@code holder} is accepted for signature parity but deliberately unused: KCP
     * keeps its own single-threaded group instead of borrowing MC's.
     */
    public static ChannelFuture connect(InetSocketAddress address, EventLoopGroupHolder holder,
                                        Connection connection) {
        KcpTransport.LOGGER.info("Connecting to {} over KCP (UDP)", address);
        Bootstrap bootstrap = new Bootstrap()
                .group(KCP_GROUP)
                .channel(UkcpClientChannel.class)
                .option(UkcpChannelOption.UKCP_NODELAY, NODELAY)
                .option(UkcpChannelOption.UKCP_INTERVAL, INTERVAL)
                .option(UkcpChannelOption.UKCP_FAST_RESEND, FAST_RESEND)
                .option(UkcpChannelOption.UKCP_NOCWND, NOCWND)
                .option(UkcpChannelOption.UKCP_MTU, MTU)
                .option(UkcpChannelOption.UKCP_SND_WND, SND_WND)
                .option(UkcpChannelOption.UKCP_RCV_WND, RCV_WND)
                .handler(new ChannelInitializer<UkcpChannel>() {
                    @Override
                    protected void initChannel(UkcpChannel channel) {
                        // The caller's Connection, not a new one: ConnectScreen keeps the object
                        // it created and sends the handshake through it after connect returns.
                        ConnectionPipeline.configure(channel.pipeline(), PacketFlow.CLIENTBOUND, connection);
                    }
                });
        return bootstrap.connect(address);
    }
}
