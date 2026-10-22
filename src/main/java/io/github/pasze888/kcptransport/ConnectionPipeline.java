package io.github.pasze888.kcptransport;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.Queue;
import net.minecraft.network.Connection;
import net.minecraft.network.RateKickingConnection;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;

/**
 * Wires the vanilla network stack onto any byte-stream channel.
 *
 * <p>One module, three callers: the server listener, the (future) client connect path, and the
 * smoke test. Keeping it in one place is what makes the smoke test evidence about the real
 * wiring instead of a hand-rolled imitation of it.
 *
 * <p>{@link #configureFraming} installs the non-packet-aware prefix of the vanilla stack, in the
 * same order and with the same handler types vanilla uses: {@code splitter}
 * ({@link Varint21FrameDecoder}), a {@link FlowControlHandler}, then {@code prepender}
 * ({@link Varint21LengthFieldPrepender}). It is exactly what
 * {@link Connection#configureSerialization} installs before its packet codecs, so a channel
 * built from it speaks the same framing the real connections do. That is the level the smoke
 * test exercises: raw bytes in, raw bytes out, no packet codec in the way.
 *
 * <p>{@link #configure} is the full stack for real connections: read timeout, the vanilla
 * serialization stack, a {@link Connection} fed into the listener's hand-off queue, and the
 * packet handler. Callers attach their own packet listener afterwards, since that is the only
 * part that differs between server and client.
 */
public final class ConnectionPipeline {

    /** Same knob as vanilla: -Dneoforge.readTimeout, default 30 seconds. */
    private static final int READ_TIMEOUT = Integer.getInteger("neoforge.readTimeout", 30);

    private ConnectionPipeline() {
    }

    /**
     * Installs the framing layer only: vanilla's splitter, flow control and length prepender,
     * without the packet codecs. Used by the smoke test, which speaks raw bytes.
     */
    public static void configureFraming(ChannelPipeline pipeline, PacketFlow flow) {
        pipeline.addLast("splitter", new Varint21FrameDecoder(null));
        pipeline.addLast(new FlowControlHandler());
        pipeline.addLast("prepender", new Varint21LengthFieldPrepender());
    }

    /**
     * Installs the full vanilla stack for a real connection and returns the {@link Connection}
     * it is bound to. A null {@code server} or {@code pending} is allowed for callers that only
     * need the pipeline; the server listener always passes both.
     */
    public static Connection configure(ChannelPipeline pipeline, PacketFlow flow,
                                       @Nullable MinecraftServer server,
                                       @Nullable Queue<Connection> pending) {
        pipeline.addLast("timeout", new ReadTimeoutHandler(READ_TIMEOUT));
        // The vanilla serialization stack, byte for byte, with no adapter in front of it.
        Connection.configureSerialization(pipeline, flow, false, null);

        int rateLimitPacketsPerSecond = server == null ? 0 : server.getRateLimitPacketsPerSecond();
        Connection connection = rateLimitPacketsPerSecond > 0
                ? new RateKickingConnection(rateLimitPacketsPerSecond)
                : new Connection(flow);

        // Same hand-off queue the TCP bootstrap feeds; ServerConnectionListener.tick() adopts it.
        if (pending != null) {
            pending.add(connection);
        }
        connection.configurePacketHandler(pipeline);
        return connection;
    }
}
