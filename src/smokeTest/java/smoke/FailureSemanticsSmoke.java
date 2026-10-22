package smoke;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;

/**
 * The failure half of issue #6: a KCP connect to a peer that is not listening must give up inside
 * the timeout guard, and must not take the TCP path down with it.
 *
 * <p>UDP has no refusal signal, so the connect itself succeeds and the connection looks alive --
 * nothing happens until the read timeout fires. That is the failure mode worth pinning: not an
 * exception, a connection that never finishes. The fixture closes only its UDP side, so TCP stays
 * up on the same port and can be re-tested right after.
 */
public final class FailureSemanticsSmoke {

    /** Generous compared with the timeout under test: this is a hang detector, not a stopwatch. */
    private static final int GUARD_SECONDS = 15;

    private static final int TIMEOUT_SECONDS = 2;

    /** A close that lands this late cannot be anything but the read timeout firing. */
    private static final long TIMEOUT_FLOOR_MS = 1_500;

    public static void main(String[] args) throws Exception {
        // Before any pipeline is built: ConnectionPipeline reads this when it adds the handler.
        System.setProperty("kcptransport.readTimeout", Integer.toString(TIMEOUT_SECONDS));

        try (DualStackServer server = DualStackServer.start()) {
            int port = server.port();
            server.stopKcp();
            System.out.println("[failure] udp/" + port + " stopped; tcp/" + port + " still listening");

            // A connect to a port with no KCP listener looks successful: that is UDP, not a bug.


            // Deliberately nothing is written on this connection. The socket is a connected UDP
            // one, so a single datagram aimed at a closed port comes back as ICMP port
            // unreachable and kills the connection within a millisecond; sending would let the
            // case pass for the wrong reason, because the guard is what has to end it.
            Connection kcp = DualStackServer.kcpClient(port);
            long startedAt = System.nanoTime();
            boolean gaveUp = awaitClosed(kcp, GUARD_SECONDS);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;
            if (!gaveUp) {
                throw new AssertionError("FAIL: a KCP connect to a peer with no listener stayed open "
                        + "for " + GUARD_SECONDS + "s instead of giving up");
            }
            // The floor is the part that actually pins the read timeout: the ceiling alone would
            // also be satisfied by an instant ICMP death.
            if (elapsedMs < TIMEOUT_FLOOR_MS) {
                throw new AssertionError("FAIL: the KCP connection ended after " + elapsedMs
                        + "ms, far below kcptransport.readTimeout=" + TIMEOUT_SECONDS
                        + "s -- something other than the read timeout closed it, so the guard is "
                        + "not what this case is testing");
            }
            System.out.println("[failure] kcp connection gave up after " + elapsedMs + "ms (guard "
                    + GUARD_SECONDS + "s, kcptransport.readTimeout=" + TIMEOUT_SECONDS + ")");

            // The point of the same-address design: TCP must be untouched by that failure.
            ClientIntentionPacket sent = new ClientIntentionPacket(DualStackServer.PROTOCOL,
                    "tcp-after-kcp-failure.local", port, ClientIntent.LOGIN);
            Connection tcp = DualStackServer.tcpClient(port);
            try {
                tcp.send(sent);
                ClientIntentionPacket decoded = server.awaitIntention(10);
                if (decoded == null) {
                    throw new AssertionError("FAIL: TCP handshake on the same address failed after "
                            + "the KCP connection gave up");
                }
                if (!decoded.equals(sent)) {
                    throw new AssertionError("FAIL: tcp decoded a different packet: sent " + sent
                            + " but got " + decoded);
                }
                System.out.println("[failure] tcp handshake on the same address still works: " + decoded);
            } finally {
                tcp.channel().close().syncUninterruptibly();
            }

            System.out.println("[failure] PASS: a dead KCP peer fails inside the guard and leaves "
                    + "TCP on the same address working");
        }
    }

    /** True once the channel is closed; false if it is still open when the guard expires. */
    private static boolean awaitClosed(Connection connection, int seconds) throws InterruptedException {
        long deadline = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (!connection.channel().isOpen()) {
                return true;
            }
            Thread.sleep(20);
        }
        return !connection.channel().isOpen();
    }
}
