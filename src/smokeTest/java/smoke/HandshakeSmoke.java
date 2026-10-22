package smoke;

import io.github.pasze888.kcptransport.kcp.UkcpChannel;
import io.netty.channel.Channel;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;

/**
 * End-to-end proof that the client wiring really runs the vanilla protocol over KCP (issue #6).
 *
 * <p>Not a hand-rolled frame: a real {@link ClientIntentionPacket} is encoded by the vanilla codec,
 * fragmented by KCP, decoded back by the vanilla codec on the other side, and compared field for
 * field. The same server is then handshaked over TCP on the same port number, which is the
 * dual-stack claim: the two transports coexist and neither needs to know about the other.
 */
public final class HandshakeSmoke {

    public static void main(String[] args) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            int port = server.port();
            System.out.println("[handshake] dual-stack endpoint on 127.0.0.1:" + port + " (tcp + kcp/udp)");

            roundTrip(server, port, "kcp-smoke.local", UkcpChannel.class,
                    () -> DualStackServer.kcpClient(port), "kcp");
            roundTrip(server, port, "tcp-smoke.local", NioSocketChannel.class,
                    () -> DualStackServer.tcpClient(port), "tcp");

            System.out.println("[handshake] same port " + port + ", two transports, two decoded packets");
            System.out.println("[handshake] PASS: a real MC handshake round-trips over KCP and TCP "
                    + "on one port without interfering");
        }
    }

    /**
     * Sends one handshake through the given client and waits for the server to decode it. The
     * equality check is the whole point: a hand-rolled frame could look right on the wire and still
     * not be a packet.
     */
    private static void roundTrip(DualStackServer server, int port, String hostName,
            Class<? extends Channel> expectedChannel, ClientOpener opener, String label)
            throws Exception {
        ClientIntentionPacket sent = new ClientIntentionPacket(
                DualStackServer.PROTOCOL, hostName, port, ClientIntent.LOGIN);
        Connection connection = opener.open();
        try {
            // The claim under test is that the vanilla protocol runs *over KCP*. Without this
            // assertion the case would keep passing if the client quietly fell back to TCP.
            if (!expectedChannel.isInstance(connection.channel())) {
                throw new AssertionError("FAIL: the " + label + " leg is not on "
                        + expectedChannel.getSimpleName() + " but on "
                        + connection.channel().getClass().getName());
            }
            System.out.println("[" + label + "] client bound to "
                    + connection.channel().getClass().getSimpleName()
                    + " local=" + connection.channel().localAddress());
            connection.send(sent);

            ClientIntentionPacket decoded = server.awaitIntention(10);
            if (decoded == null) {
                throw new AssertionError("FAIL: the handshake sent over " + label
                        + " never reached the server");
            }
            if (!decoded.equals(sent)) {
                throw new AssertionError("FAIL: " + label + " decoded a different packet: sent "
                        + sent + " but got " + decoded);
            }
            // Reached the agreed port, over the agreed transport: not just "somewhere".
            if (!(connection.channel().remoteAddress() instanceof InetSocketAddress remote)
                    || remote.getPort() != port) {
                throw new AssertionError("FAIL: " + label + " connected somewhere else: "
                        + connection.channel().remoteAddress());
            }
            System.out.println("[" + label + "] decoded " + decoded);
        } finally {
            connection.channel().close().syncUninterruptibly();
        }
    }

    @FunctionalInterface
    private interface ClientOpener {
        Connection open() throws Exception;
    }
}
