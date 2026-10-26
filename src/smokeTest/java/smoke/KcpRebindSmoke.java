package smoke;

import io.github.pasze888.kcptransport.kcp.base.IChannelManager;
import io.github.pasze888.kcptransport.kcp.base.Ukcp;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pins the server's session routing after the rollback of #21 (issue #22).
 *
 * <p>#21 keyed the sessions by source address <em>and</em> by conv, and moved a session to a new
 * source address whenever the conv matched. #22 drops the conv index: the server keys its
 * sessions by source address alone, so a client that comes back from a different port is a
 * different peer, and the old session is not carried over. The one rebuild the server still does
 * is the one the ticket names: the same ip:port, a conv the session does not have, and a first
 * segment (sn == 0) means "this is a new conversation on this address" -- the old session is
 * closed and a new one is built.
 *
 * <p>Every control is printed with its own verdict and the run fails if any of them failed, so
 * one broken rule does not hide the state of the others. The datagrams are built by hand rather
 * than by a second client, because a client negotiates its own conv and its own segment numbers,
 * and these controls have to pin both.
 *
 * <p>The payload of every segment is one byte that reads as a frame length of 127. It cannot be
 * empty: the vendored kernel builds a segment without a buffer for a zero-length payload
 * (Kcp.createSegment leaves the segment data null and the read path dereferences it), and the
 * session is torn down. It cannot be a valid Minecraft frame either, so it is one byte that
 * reads as a frame length of 127: the frame decoder waits for the rest of a frame that never
 * comes, which leaves the session alone instead of failing it.
 */
public final class KcpRebindSmoke {

    /** conv(4) cmd(1) frg(1) wnd(2) ts(4) sn(4) una(4) len(4). */
    private static final int KCP_OVERHEAD = 24;
    private static final int SN_OFFSET = 12;
    private static final byte CMD_PUSH = 81;
    private static final byte CMD_ACK = 82;

    /**
     * The payload every segment carries.
     *
     * <p>It cannot be empty: the kernel builds a segment without a buffer for a zero-length
     * payload, and the read path then trips over it and tears the session down. It cannot be a
     * valid Minecraft frame either, so it is one byte that reads as a frame length of 127: the
     * frame decoder waits for the rest of a frame that never comes, which leaves the session
     * alone instead of failing it.
     */
    private static final byte[] PAYLOAD = {0x7F};

    private static final int CONV_LIVE = 0x11C0FFEE;
    private static final int CONV_OTHER = 0x22222222;
    private static final int CONV_THIRD = 0x33333333;
    private static final int CONV_FOREIGN = 0x7FFF0000;

    public static void main(String[] args) throws Exception {
        List<String> failures = new ArrayList<>();
        n1PortChangeDoesNotMigrate(failures);
        p1DistinctConvsGetTheirOwnSession(failures);
        n2UnknownConvNonFirstSegmentIsDropped(failures);
        knownAddressControls(failures);
        n6AddressTableGrowsToThreeAndDrainsToZero(failures);
        if (!failures.isEmpty()) {
            throw new AssertionError("FAIL: " + failures.size() + " control(s) failed:\n  "
                    + String.join("\n  ", failures));
        }
        System.out.println("PASS: sessions are keyed by source address only, and a session is rebuilt"
                + " only for a new conv arriving with a first segment");
    }

    /**
     * N1: a change of source port must not carry the session over.
     *
     * <p>The live session is built by hand so that its receive window is pinned: two in-order
     * segments put its rcvNxt at 2, so the probe below (sn == 1) is a duplicate of a segment the
     * session already has. The kernel re-ACKs a duplicate, which is what makes this control red
     * while the #21 migration is still in place: the migrated session answers the probe at the
     * new port.
     *
     * <p>Under the rollback the probe is not a first segment, so the server drops it the way it
     * drops any other traffic from an unknown peer: no session is created, no ACK comes back,
     * and the session that is still there is the one that was there all along.
     */
    private static void n1PortChangeDoesNotMigrate(List<String> failures) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.port());
            int conv = CONV_LIVE;
            try (DatagramChannel live = bind()) {
                live.send(push(conv, 0, PAYLOAD), serverAddress);
                if (!awaitAckSn(live, 3000, 0)) {
                    fail(failures, "N1", "the server did not answer the first segment; no session was built");
                    return;
                }
                live.send(push(conv, 1, PAYLOAD), serverAddress);
                if (!awaitAckSn(live, 3000, 1)) {
                    fail(failures, "N1", "the server did not answer the second segment;"
                            + " the session did not take it in");
                    return;
                }
                Ukcp session = awaitSession(server, conv);
                if (session == null) {
                    fail(failures, "N1", "the session table does not hold conv " + conv);
                    return;
                }
                try (DatagramChannel rebound = bind()) {
                    rebound.send(push(conv, 1, PAYLOAD), serverAddress);
                    Integer ackSn = awaitAck(rebound, 1500);
                    if (ackSn != null) {
                        fail(failures, "N1", "an ACK (sn=" + ackSn + ") reached the new source port:"
                                + " the session followed the port change, which #22 rolls back");
                        return;
                    }
                    Collection<Ukcp> after = sessions(server);
                    if (after.size() != 1) {
                        fail(failures, "N1", "expected 1 session after the port change, saw "
                                + after.size() + " convs=" + convs(server));
                    } else if (!after.contains(session)) {
                        fail(failures, "N1", "the session was replaced instead of kept");
                    } else {
                        pass("N1", "no ACK at the new port; the same session (conv " + conv
                                + ") is still the only one");
                    }
                }
            }
        }
    }

    /**
     * P1: two clients with two convs get two independent sessions.
     *
     * <p>This is the rule the conv index used to serve and the address table serves just as well:
     * a first segment from an unknown peer builds a session, and each session keeps its own conv.
     */
    private static void p1DistinctConvsGetTheirOwnSession(List<String> failures) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.port());
            try (DatagramChannel first = bind(); DatagramChannel second = bind()) {
                first.send(push(CONV_LIVE, 0, PAYLOAD), serverAddress);
                second.send(push(CONV_OTHER, 0, PAYLOAD), serverAddress);
                boolean firstAcked = awaitAckSn(first, 3000, 0);
                boolean secondAcked = awaitAckSn(second, 3000, 0);
                if (!firstAcked || !secondAcked) {
                    fail(failures, "P1", "the first segments were not answered (first=" + firstAcked
                            + ", second=" + secondAcked + ")");
                    return;
                }
                Collection<Ukcp> all = sessions(server);
                if (all.size() != 2) {
                    fail(failures, "P1", "expected 2 sessions, saw " + all.size() + " convs=" + convs(server));
                    return;
                }
                if (!holdsConv(all, CONV_LIVE) || !holdsConv(all, CONV_OTHER)) {
                    fail(failures, "P1", "the two sessions do not carry the two convs: " + convs(server));
                    return;
                }
                pass("P1", "two convs, two independent sessions (" + convs(server) + ")");
            }
        }
    }

    /**
     * N2: a non-first segment from an unknown conv is still dropped.
     *
     * <p>This is the rule the rollback must not widen: a port probe or a stray datagram must not
     * create a session.
     */
    private static void n2UnknownConvNonFirstSegmentIsDropped(List<String> failures) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.port());
            try (DatagramChannel live = bind(); DatagramChannel probe = bind()) {
                live.send(push(CONV_LIVE, 0, PAYLOAD), serverAddress);
                if (!awaitAckSn(live, 3000, 0)) {
                    fail(failures, "N2", "the server did not answer the first segment;"
                            + " no session was built to probe");
                    return;
                }
                probe.send(push(CONV_FOREIGN, 99, PAYLOAD), serverAddress);
                if (awaitAck(probe, 800) != null) {
                    fail(failures, "N2", "a non-first segment from an unknown conv was answered");
                    return;
                }
                Ukcp liveSession = awaitSession(server, CONV_LIVE);
                if (liveSession == null) {
                    fail(failures, "N2", "the table does not hold the session the probe was made against");
                    return;
                }
                // "No session was created" is watched over a window, not read once: a child that
                // was just built is invisible in the manager view until its kernel is there
                // (ukcp() is null until doRegister ran), so an instant count can miss it.
                TableWatch watch = watch(server.kcpChannel(),
                        entries -> entries.size() == 1 && entries.contains(liveSession), 500);
                if (watch.mismatch != null) {
                    fail(failures, "N2", "a non-first segment from an unknown conv changed the table: "
                            + watch.mismatch + " (entries between " + watch.minCount + " and "
                            + watch.maxCount + ")");
                    return;
                }
                pass("N2", "the non-first segment from an unknown conv was dropped");
            }
        }
    }

    /**
     * N3, N4 and N5: the three rules for an address the server already knows.
     *
     * <p>They share one session, because each of them is about the same ip:port.
     */
    private static void knownAddressControls(List<String> failures) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.port());
            int conv = CONV_LIVE;
            try (DatagramChannel peer = bind()) {
                peer.send(push(conv, 0, PAYLOAD), serverAddress);
                if (!awaitAckSn(peer, 3000, 0)) {
                    fail(failures, "N3", "the server did not answer the first segment; no session was built");
                    fail(failures, "N4", "the server did not answer the first segment; no session was built");
                    fail(failures, "N5", "the server did not answer the first segment; no session was built");
                    return;
                }
                peer.send(push(conv, 1, PAYLOAD), serverAddress);
                if (!awaitAckSn(peer, 3000, 1)) {
                    fail(failures, "N3", "the server did not answer the second segment;"
                            + " the session did not take it in");
                    fail(failures, "N4", "the server did not answer the second segment;"
                            + " the session did not take it in");
                    fail(failures, "N5", "the server did not answer the second segment;"
                            + " the session did not take it in");
                    return;
                }
                Ukcp session = awaitSession(server, conv);
                if (session == null) {
                    fail(failures, "N3", "the session table does not hold conv " + conv);
                    fail(failures, "N4", "the session table does not hold conv " + conv);
                    fail(failures, "N5", "the session table does not hold conv " + conv);
                    return;
                }
                n3SameConvKeepsTheSession(failures, server, peer, serverAddress, conv, session);
                n4NewConvNonFirstSegmentIsDropped(failures, server, peer, serverAddress, conv, session);
                n5NewConvFirstSegmentRebuildsTheSession(failures, server, peer, serverAddress, conv, session);
            }
        }
    }

    /**
     * N3: the same ip:port with the same conv is an ordinary packet: the session is not rebuilt
     * and it is the same instance.
     */
    private static void n3SameConvKeepsTheSession(List<String> failures, DualStackServer server,
            DatagramChannel peer, InetSocketAddress serverAddress, int conv, Ukcp session) throws Exception {
        peer.send(push(conv, 1, PAYLOAD), serverAddress);
        if (!awaitAckSn(peer, 3000, 1)) {
            fail(failures, "N3", "the session did not answer a packet with its own conv");
            return;
        }
        Collection<Ukcp> all = sessions(server);
        if (all.size() != 1) {
            fail(failures, "N3", "expected 1 session, saw " + all.size() + " convs=" + convs(server));
            return;
        }
        if (!all.contains(session)) {
            fail(failures, "N3", "the session was rebuilt; it is not the same instance");
            return;
        }
        pass("N3", "the same conv kept the session; it is the same instance");
    }

    /**
     * N4: the same ip:port with a new conv, but not a first segment, is dropped: no session is
     * built, none is destroyed, and the count does not move.
     */
    private static void n4NewConvNonFirstSegmentIsDropped(List<String> failures, DualStackServer server,
            DatagramChannel peer, InetSocketAddress serverAddress, int conv, Ukcp session) throws Exception {
        drain(peer);
        peer.send(push(CONV_OTHER, 5, PAYLOAD), serverAddress);
        if (awaitAck(peer, 800) != null) {
            fail(failures, "N4", "a non-first segment with a new conv was answered");
            return;
        }
        // The table is watched over a window instead of read once, and the identity of the
        // entry is part of what is expected: the table is keyed by the remote address, so a
        // session built for this peer would replace the entry rather than add one, and the count
        // would not move. The entry that stays has to be the same session, with the same conv.
        TableWatch watch = watch(server.kcpChannel(), entries -> entries.size() == 1
                && entries.contains(session) && entries.iterator().next().getConv() == conv, 500);
        if (watch.mismatch != null) {
            fail(failures, "N4", "a non-first segment with a new conv was not ignored: "
                    + watch.mismatch + " (entries between " + watch.minCount + " and "
                    + watch.maxCount + ")");
            return;
        }
        pass("N4", "the non-first segment with a new conv was dropped; the session is untouched");
    }

    /**
     * N5: the same ip:port with a new conv and a first segment is a new conversation: the old
     * session is closed, a new one is built, and the count settles back to one.
     */
    private static void n5NewConvFirstSegmentRebuildsTheSession(List<String> failures, DualStackServer server,
            DatagramChannel peer, InetSocketAddress serverAddress, int conv, Ukcp session) throws Exception {
        drain(peer);
        peer.send(push(CONV_OTHER, 0, PAYLOAD), serverAddress);
        Ukcp rebuilt = null;
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            Collection<Ukcp> all = sessions(server);
            if (all.size() == 1) {
                Ukcp only = all.iterator().next();
                if (only != session && only.getConv() == CONV_OTHER) {
                    rebuilt = only;
                    break;
                }
            }
            Thread.sleep(10);
        }
        if (rebuilt == null) {
            fail(failures, "N5", "a first segment with a new conv did not build a new session;"
                    + " the table holds " + sessions(server).size() + " convs=" + convs(server));
            return;
        }
        if (!awaitAckSn(peer, 3000, 0)) {
            fail(failures, "N5", "the rebuilt session did not answer the first segment that built it");
            return;
        }
        // The close of the old session lands on its own event loop, so it is not done the moment
        // the new one is in place.
        deadline = System.currentTimeMillis() + 2000;
        while (session.isActive() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (session.isActive()) {
            fail(failures, "N5", "the old session is still active; it was not closed");
            return;
        }
        pass("N5", "the old session was closed and a new one (conv " + CONV_OTHER + ") took its place");
    }

    /**
     * N6: the address table grows to three sessions and drains back to zero when the parent
     * channel closes.
     *
     * <p>This is the "删" half of the ticket's 增删查 line, and it can only be observed through the
     * parent's own close path. A peer that goes silent is never noticed: the vendored kernel's
     * dead-link check is commented out, so a session outlives the socket
     * on the other end, and closing the three peers would prove nothing at all.
     *
     * <p>What does remove a session is the parent channel closing: {@code UkcpServerChannel.doClose}
     * walks the table and closes every child, and each child removes itself by value in
     * {@code UkcpServerChildChannel.doClose} -> {@code parent().removeChild(this)}. So the control
     * builds three sessions from three bound sockets with three distinct convs (each first segment
     * has sn == 0), checks that the table holds three, closes the parent through
     * {@code DualStackServer.stopKcp()}, and polls the captured channel's manager view until it is
     * empty again.
     *
     * <p>The channel reference is taken before {@code stopKcp()}, because {@code kcpChannel()}
     * returns null once the UDP side is down.
     */
    private static void n6AddressTableGrowsToThreeAndDrainsToZero(List<String> failures) throws Exception {
        try (DualStackServer server = DualStackServer.start()) {
            InetSocketAddress serverAddress = new InetSocketAddress("127.0.0.1", server.port());
            // Captured before stopKcp(): the fixture's reference is null once the UDP side is down.
            Channel parent = server.kcpChannel();
            int[] convs = {CONV_LIVE, CONV_OTHER, CONV_THIRD};
            StringBuilder convList = new StringBuilder();
            for (int conv : convs) {
                convList.append(conv).append(' ');
            }
            try (DatagramChannel first = bind(); DatagramChannel second = bind(); DatagramChannel third = bind()) {
                DatagramChannel[] peers = {first, second, third};
                for (int i = 0; i < peers.length; i++) {
                    peers[i].send(push(convs[i], 0, PAYLOAD), serverAddress);
                }
                for (int i = 0; i < peers.length; i++) {
                    if (!awaitAckSn(peers[i], 3000, 0)) {
                        fail(failures, "N6", "the first segment of peer " + i + " (conv " + convs[i]
                                + ") was not answered; there is no session to count");
                        return;
                    }
                }
                // The table is written on the server's read loop, so it is counted by polling
                // rather than read once.
                Collection<Ukcp> all = sessionsOf(parent);
                long deadline = System.currentTimeMillis() + 2000;
                while (all.size() != 3 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                    all = sessionsOf(parent);
                }
                if (all.size() != 3) {
                    fail(failures, "N6", "expected 3 sessions in the address table, saw " + all.size()
                            + " convs=" + convsOf(parent));
                    return;
                }
                // And it has to stay there, with the three convs and three live sessions: the
                // drain below has to be caused by the parent's close and not by sessions that
                // were already gone. The window also rules out a count that only looked right
                // because a child was still invisible (ukcp() is null until doRegister ran).
                TableWatch watch = watch(parent, entries -> entries.size() == 3
                        && entries.stream().allMatch(Ukcp::isActive)
                        && holdsConv(entries, CONV_LIVE)
                        && holdsConv(entries, CONV_OTHER)
                        && holdsConv(entries, CONV_THIRD), 500);
                if (watch.mismatch != null) {
                    fail(failures, "N6", "the address table did not hold three live sessions: "
                            + watch.mismatch + " (entries between " + watch.minCount + " and "
                            + watch.maxCount + ")");
                    return;
                }
                server.stopKcp();
                // Each child closes on its own event loop, so the table drains a turn later.
                deadline = System.currentTimeMillis() + 2000;
                while (!sessionsOf(parent).isEmpty() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                Collection<Ukcp> after = sessionsOf(parent);
                if (!after.isEmpty()) {
                    fail(failures, "N6", "the address table did not drain after the parent closed: "
                            + after.size() + " session(s) left convs=" + convsOf(parent));
                    return;
                }
                // And it has to stay drained. The kernel of a child is never unset, so an empty
                // view means the table itself is empty; the window proves that nothing fills it
                // up again.
                TableWatch drained = watch(parent, entries -> entries.isEmpty(), 500);
                if (drained.mismatch != null) {
                    fail(failures, "N6", "the address table filled up again after it drained: "
                            + drained.mismatch);
                    return;
                }
                pass("N6", "three sessions (convs " + convList.toString().trim()
                        + ") were in the address table, and closing the parent channel drained it to 0");
            }
        }
    }

    /** Opens a UDP socket on an ephemeral loopback port. */
    private static DatagramChannel bind() throws Exception {
        DatagramChannel channel = DatagramChannel.open();
        channel.bind(new InetSocketAddress("127.0.0.1", 0));
        return channel;
    }

    /** Throws away whatever is already waiting on the socket. */
    private static void drain(DatagramChannel channel) throws Exception {
        channel.configureBlocking(false);
        ByteBuffer buf = ByteBuffer.allocate(256);
        while (channel.receive(buf) != null) {
            buf.clear();
        }
    }

    private static void pass(String id, String message) {
        System.out.println("[" + id + "] PASS " + message);
    }

    private static void fail(List<String> failures, String id, String message) {
        System.out.println("[" + id + "] FAIL " + message);
        failures.add(id + ": " + message);
    }

    /**
     * Waits for the session that carries {@code conv}. The object is returned rather than its
     * conv so a control can assert that a session was <em>kept</em>, not merely that one exists.
     */
    private static Ukcp awaitSession(DualStackServer server, int conv) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            for (Ukcp ukcp : sessions(server)) {
                if (ukcp.getConv() == conv) {
                    return ukcp;
                }
            }
            Thread.sleep(10);
        }
        return null;
    }

    private static Collection<Ukcp> sessions(DualStackServer server) {
        return ((IChannelManager) server.kcpChannel()).getAll();
    }

    /**
     * The manager view of a captured channel. The fixture drops its own reference to the UDP
     * listener when that listener is closed, so a control that watches the table across a close
     * has to hold the channel itself.
     */
    private static Collection<Ukcp> sessionsOf(Channel parent) {
        return ((IChannelManager) parent).getAll();
    }

    private static boolean holdsConv(Collection<Ukcp> sessions, int conv) {
        for (Ukcp ukcp : sessions) {
            if (ukcp.getConv() == conv) {
                return true;
            }
        }
        return false;
    }

    private static String convs(DualStackServer server) {
        StringBuilder sb = new StringBuilder();
        for (Ukcp ukcp : sessions(server)) {
            sb.append(ukcp.getConv()).append(' ');
        }
        return sb.toString();
    }

    /** The convs of the sessions a captured channel still holds. */
    private static String convsOf(Channel parent) {
        return convsOf(sessionsOf(parent));
    }

    /** The convs of the given sessions. */
    private static String convsOf(Collection<Ukcp> sessions) {
        StringBuilder sb = new StringBuilder();
        for (Ukcp ukcp : sessions) {
            sb.append(ukcp.getConv()).append(' ');
        }
        return sb.toString();
    }

    /**
     * What the address table held at every poll of a watch window.
     *
     * <p>{@code minCount} and {@code maxCount} are the smallest and the largest number of entries
     * the manager view reported over the window; {@code mismatch} holds the first observation that
     * did not fit what the control expected, or null when every poll fitted.
     */
    private static final class TableWatch {
        private int minCount = Integer.MAX_VALUE;
        private int maxCount = -1;
        private String mismatch;
    }

    /** Whether the observed table is the one the control expects. */
    private interface TableExpectation {
        boolean matches(Collection<Ukcp> all);
    }

    /**
     * Watches the address table of {@code parent} for {@code windowMillis} and reports every
     * observation.
     *
     * <p>A single read cannot claim "no session was created": {@code getAll()} reports only the
     * children whose kernel is already built (UkcpServerChildChannel.ukcp() is null until
     * doRegister ran), so a session that was just built is invisible for a moment and an instant
     * count can miss it. Watching over a window gives such a session the time to register and to
     * show up.
     *
     * <p>The count is not the whole answer either: the table is keyed by the remote address, so
     * a session built for a peer that is already known replaces the entry instead of adding one,
     * and the count would not move. What catches that is the identity of the entry and its conv,
     * and neither has the registration hole, because the new child replaces the map entry
     * synchronously when it is created.
     */
    private static TableWatch watch(Channel parent, TableExpectation expectation, long windowMillis)
            throws InterruptedException {
        TableWatch watch = new TableWatch();
        long deadline = System.currentTimeMillis() + windowMillis;
        do {
            Collection<Ukcp> all = sessionsOf(parent);
            watch.minCount = Math.min(watch.minCount, all.size());
            watch.maxCount = Math.max(watch.maxCount, all.size());
            if (watch.mismatch == null && !expectation.matches(all)) {
                watch.mismatch = "the table held " + all.size() + " session(s) convs=" + convsOf(all);
            }
            Thread.sleep(10);
        } while (System.currentTimeMillis() < deadline);
        return watch;
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

    /** Waits for the ACK of one specific {@code sn}; other ACKs are noise for this assertion. */
    private static boolean awaitAckSn(DatagramChannel channel, long timeoutMillis, int expectedSn) throws Exception {
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
            if (buf.remaining() >= KCP_OVERHEAD && buf.get(4) == CMD_ACK
                    && buf.order(ByteOrder.LITTLE_ENDIAN).getInt(SN_OFFSET) == expectedSn) {
                return true;
            }
        }
        return false;
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
