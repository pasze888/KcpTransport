package smoke;

import io.github.pasze888.kcptransport.kcp.base.IChannelManager;
import io.github.pasze888.kcptransport.kcp.base.Ukcp;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.Collection;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;

/**
 * Proves a session survives a change of source port, which is what a NAT rebinding looks like from
 * the server side: same conv, different {@code ip:port}.
 *
 * <p>Issue #21 / ADR-0008. Before this change the server found a session by remote address only, so
 * the first datagram from the new port fell through to the "unknown peer" branch and was dropped
 * whenever its {@code sn} was not zero. The client kept retransmitting and the connection died on
 * the vanilla 30 s read timeout.
 *
 * <p>The datagram is built by hand rather than by a second client, because a second client would
 * negotiate its own conv. A hand-built PUSH with the <em>existing</em> number is the
 * only way to say "this is the same session, from somewhere else".
 *
 * <p>What this asserts, and only this: an ACK comes back <em>to the new port</em>, and the session
 * count stays one. It does not assert the migration log line, and it does not assert that
 * {@code Connection.getRemoteAddress()} follows the move — the ticket explicitly leaves the
 * Minecraft-side address stale.
 */
public final class KcpRebindSmoke {

    /** conv(4) cmd(1) frg(1) wnd(2) ts(4) sn(4) una(4) len(4). */
    private static final int KCP_OVERHEAD = 24;
    private static final int SN_OFFSET = 12;
    private static final byte CMD_PUSH = 81;
    private static final byte CMD_ACK = 82;

    public static void main(String[] args) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            Connection client = DualStackServer.kcpClient(server.port());
            // connect() only opens the UDP socket; a KCP session is built on the first datagram.
            // A real vanilla packet makes that first datagram a real one.
            client.send(new ClientIntentionPacket(
                    DualStackServer.PROTOCOL, "rebind.local", server.port(), ClientIntent.LOGIN));
            Ukcp session = awaitSession(server);
            int conv = session.getConv();
            System.out.println("[rebind] session up, conv=" + conv);

            try (DatagramChannel rebound = DatagramChannel.open()) {
                rebound.bind(new InetSocketAddress("127.0.0.1", 0));
                int newPort = ((InetSocketAddress) rebound.getLocalAddress()).getPort();

                // A PUSH with the live conv, from a port the server has never seen.
                // sn stays below rcvNxt, so the kernel acknowledges it without delivering a second
                // copy of the payload — the ACK is the whole point, not the data.
                ByteBuffer push = push(conv, 0, "rebind".getBytes());
                rebound.send(push, new InetSocketAddress("127.0.0.1", server.port()));

                // 1.5 s, not 3: a fix that quietly grows a waiting period would still pass a loose
                // deadline, and the ticket decided migrations take effect at once.
                Integer ackSn = awaitAck(rebound, 1500);
                if (ackSn == null) {
                    throw new AssertionError("FAIL: no ACK reached the new source port (" + newPort
                            + "); the session did not migrate, so the rebinding dropped it");
                }
                System.out.println("[rebind] ACK sn=" + ackSn + " reached new source port " + newPort);

                // A migration must reuse the session object, not replace it. Two checks, because
                // neither alone is enough:
                //  - the count catches "the new address opened a second session" (what the old
                //    address-keyed routing did), and
                //  - the identity catches "the old session closed and a new one took its place",
                //    which the count alone would miss -- the tally would be back at one and this
                //    case would pass while no migration happened.
                // Both hold only because the client stays connected for the whole case: its session
                // is never closed, so a live session at the end can only be the one we started with.
                Collection<Ukcp> live = sessions(server);
                if (live.size() != 1) {
                    throw new AssertionError("FAIL: expected 1 session after rebinding, saw "
                            + live.size() + " convs=" + convs(server));
                }
                if (!live.contains(session)) {
                    throw new AssertionError("FAIL: the session was rebuilt, not migrated");
                }
                System.out.println("[rebind] same session object, count still 1");
            }

            // Negative control 1: a different conv from a new port is a different
            // session, not a migration. Without this, a fix that migrated on "unknown address"
            // alone would pass the positive case and quietly merge unrelated players.
            int strangerConv = conv ^ 0x5A5A5A5A;
            try (DatagramChannel stranger = DatagramChannel.open()) {
                stranger.bind(new InetSocketAddress("127.0.0.1", 0));
                stranger.send(push(strangerConv, 0, "stranger".getBytes()),
                        new InetSocketAddress("127.0.0.1", server.port()));
                if (awaitAck(stranger, 3000) == null) {
                    throw new AssertionError("FAIL: a first segment with a new conv was not answered;"
                            + " the server stopped creating sessions");
                }
                int sessions = sessions(server).size();
                if (sessions != 2) {
                    throw new AssertionError("FAIL: a different conv must open its own session, saw "
                            + sessions + " (migration must key on conv, not on 'unknown address')");
                }
                System.out.println("[rebind] different conv opened its own session (count=" + sessions + ")");
            }

            // Negative control 2: an unknown conv whose first segment is not sn==0 is still
            // dropped. This is the rule the migration path must not widen -- a port probe or a
            // stray datagram must not create a session.
            try (DatagramChannel probe = DatagramChannel.open()) {
                probe.bind(new InetSocketAddress("127.0.0.1", 0));
                probe.send(push(conv ^ 0x7FFF0000, 99, "probe".getBytes()),
                        new InetSocketAddress("127.0.0.1", server.port()));
                if (awaitAck(probe, 800) != null) {
                    throw new AssertionError("FAIL: a non-first segment from an unknown conv was answered");
                }
                int sessions = sessions(server).size();
                if (sessions != 2) {
                    throw new AssertionError("FAIL: a non-first segment from an unknown conv created a session, saw "
                            + sessions + " convs=" + convs(server));
                }
                System.out.println("[rebind] non-first segment from an unknown conv was dropped");
            }

            client.channel().close();
        }
        System.out.println("PASS: KCP session followed the source port change");
    }

    /**
     * Waits for the session the client just opened. The KCP handshake needs a round trip, so the
     * table is polled instead of read once.
     *
     * <p>The object is returned rather than its conv so the positive case can assert that a
     * migration kept <em>this</em> session, not merely that one session exists.
     */
    private static Ukcp awaitSession(DualStackServer server) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            Collection<Ukcp> all = sessions(server);
            if (!all.isEmpty()) {
                return all.iterator().next();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("FAIL: no KCP session was established");
    }

    private static Collection<Ukcp> sessions(DualStackServer server) {
        return ((IChannelManager) server.kcpChannel()).getAll();
    }

    private static String convs(DualStackServer server) {
        StringBuilder sb = new StringBuilder();
        for (Ukcp ukcp : sessions(server)) {
            sb.append(ukcp.getConv()).append(' ');
        }
        return sb.toString();
    }

    /** Returns the {@code sn} of the first ACK seen, or null when the deadline passes. */
    private static Integer awaitAck(DatagramChannel channel, long timeoutMillis) throws Exception {
        channel.configureBlocking(false);
        ByteBuffer buf = ByteBuffer.allocate(256);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            buf.clear();
            if (channel.receive(buf) == null) {
                Thread.sleep(5);
                continue;
            }
            buf.flip();
            if (buf.remaining() >= KCP_OVERHEAD && buf.get(4) == CMD_ACK) {
                return buf.order(ByteOrder.LITTLE_ENDIAN).getInt(SN_OFFSET);
            }
            // Window probes and anything else are noise for this assertion.
        }
        return null;
    }

    private static ByteBuffer push(int conv, int sn, byte[] payload) {
        ByteBuffer buf = ByteBuffer.allocate(KCP_OVERHEAD + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(conv);
        buf.put(CMD_PUSH);
        buf.put((byte) 0);
        buf.putShort((short) 128);
        buf.putInt((int) (System.currentTimeMillis() & 0xFFFFFFFFL));
        buf.putInt(sn);
        buf.putInt(0);
        buf.putInt(payload.length);
        buf.put(payload);
        buf.flip();
        return buf;
    }

    private KcpRebindSmoke() {
    }
}
