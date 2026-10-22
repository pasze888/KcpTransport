package smoke;

import io.github.pasze888.kcptransport.KcpClientBootstrap;
import io.netty.channel.ChannelFuture;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;

/**
 * Pins the client bootstrap's binding contract: the {@link Connection} the caller hands over is
 * the one that ends up on the channel.
 *
 * <p>{@code ConnectScreen} creates the Connection itself, keeps that object, and sends the
 * handshake through it once connect returns. A bootstrap that wires a fresh Connection instead
 * leaves the caller's channel null, and the first send dies in {@code sendPacket}. That failure
 * mode is invisible to the compiler and to the framing smokes, so it gets its own check here.
 */
public final class ClientConnectionSmoke {

    public static void main(String[] args) throws Exception {
        // Nothing listens on this port. UDP has no refusal signal, so connect still succeeds --
        // which is exactly the case that must not throw before the Connection is bound.
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 25999);
        Connection connection = new Connection(PacketFlow.CLIENTBOUND);

        ChannelFuture future = KcpClientBootstrap.connect(address, null, connection);
        future.syncUninterruptibly();

        // The connect promise completes before fireChannelActive() runs on the event loop, so
        // the channel is bound a moment after sync() returns.
        for (int i = 0; i < 300 && connection.channel() == null; i++) {
            Thread.sleep(10);
        }
        if (connection.channel() == null) {
            throw new AssertionError(
                    "FAIL: the Connection passed to KcpClientBootstrap never got a channel");
        }

        System.out.println("[client] PASS: caller's Connection bound to "
                + connection.channel().getClass().getSimpleName());
        connection.channel().close().syncUninterruptibly();
    }
}
