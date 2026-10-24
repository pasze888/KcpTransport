/*
 * KcpTransport: kernel self-test for the vendored java-kcp base (issue #13).
 *
 * <p>Deliberately does not build a Netty channel: this ticket only lands the new base, the wiring
 * comes next. What is checked here is the state machine itself, exercised over an in-memory wire
 * that hands one side's datagrams to the other side's input:
 *
 * <ul>
 *   <li>a payload larger than one MTU round-trips byte for byte (so fragmentation is exercised);</li>
 *   <li>two conversations on the same wire only accept their own conv, so sessions cannot cross-talk;</li>
 *   <li>junk bytes are rejected by a negative return code instead of an exception -- the channel
 *       layer treats exceptions as fatal, which is how a port scan used to kill a live session.</li>
 *   <li>every datagram the kernel emits carries a standard 24-byte KCP header and nothing else, so
 *       the base swap did not quietly change the wire format (conv/cmd/frg/wnd/ts/sn/una/len).</li>
 * </ul>
 */
package smoke;

import io.github.pasze888.kcptransport.kcp.base.IKcp;
import io.github.pasze888.kcptransport.kcp.base.Kcp;
import io.github.pasze888.kcptransport.kcp.base.KcpOutput;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

public final class KernelSmoke {

    private static final int CONV_A = 0x5eed;
    private static final int CONV_B = 0xbeef;
    private static final int MAX_STEPS = 400;
    private static final long STEP_MS = 10;

    /** Standard KCP header, as every implementation on the wire agrees on it. */
    private static final int HEADER_SIZE = 24;
    /** IKCP_CMD_PUSH from the KCP spec (0x51); ACK is 82. */
    private static final int CMD_PUSH = 81;
    private static final int MTU = 1400;
    private static final int MSS = MTU - HEADER_SIZE;

    public static void main(String[] args) {
        roundTrip();
        twoConversationsDoNotCrossTalk();
        junkIsRejectedWithoutThrowing();
        headerLayoutIsStandard();
        System.out.println("[kernel] PASS: the vendored kernel round-trips a multi-segment payload, "
                + "keeps two conversations apart, rejects junk without throwing, and emits the "
                + "standard 24-byte header");
    }

    /** One payload spanning several segments must come out of the other side unchanged. */
    private static void roundTrip() {
        Wire fromA = new Wire();
        Wire fromB = new Wire();
        Kcp a = new Kcp(CONV_A, fromA);
        Kcp b = new Kcp(CONV_A, fromB);

        String line = "kcp-base over an in-memory wire; ";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 120; i++) {
            sb.append(line);
        }
        byte[] payload = sb.toString().getBytes(StandardCharsets.UTF_8);

        a.send(Unpooled.wrappedBuffer(payload));
        ByteBuf got = readUntilDelivered(a, fromA, b, fromB);
        try {
            if (got == null) {
                throw new AssertionError("FAIL: the payload never arrived over the kernel");
            }
            if (got.readableBytes() != payload.length) {
                throw new AssertionError("FAIL: expected " + payload.length
                        + " bytes back, got " + got.readableBytes());
            }
            byte[] actual = new byte[got.readableBytes()];
            got.getBytes(got.readerIndex(), actual);
            if (!java.util.Arrays.equals(payload, actual)) {
                throw new AssertionError("FAIL: the kernel changed the payload in flight");
            }
        } finally {
            if (got != null) {
                got.release();
            }
        }
        System.out.println("[kernel] round-trip " + payload.length + " bytes over "
                + ((payload.length + 1375) / 1376) + " segment(s)");
    }

    /**
     * Two conversations share one wire. Each side must accept its own conv and answer the other
     * with -4, otherwise multiplexing on one UDP port would deliver packets to the wrong player.
     */
    private static void twoConversationsDoNotCrossTalk() {
        Wire a1Out = new Wire();
        Wire b1Out = new Wire();
        Wire a2Out = new Wire();
        Wire b2Out = new Wire();
        Kcp a1 = new Kcp(CONV_A, a1Out);
        Kcp b1 = new Kcp(CONV_A, b1Out);
        Kcp a2 = new Kcp(CONV_B, a2Out);
        Kcp b2 = new Kcp(CONV_B, b2Out);

        a1.send(Unpooled.wrappedBuffer("one".getBytes(StandardCharsets.UTF_8)));
        a2.send(Unpooled.wrappedBuffer("two".getBytes(StandardCharsets.UTF_8)));

        int rejected = 0;
        long now = System.currentTimeMillis();
        for (int step = 0; step < MAX_STEPS; step++) {
            now += STEP_MS;
            a1.update(now);
            a2.update(now);
            // Feed each side's datagrams to BOTH receivers: the foreign one must reject them.
            rejected += feedCross(a1Out, b1, b2, now);
            rejected += feedCross(a2Out, b2, b1, now);
            b1.update(now);
            b2.update(now);
            drain(b1Out, a1, now);
            drain(b2Out, a2, now);

            ByteBuf fromB1 = b1.mergeRecv();
            ByteBuf fromB2 = b2.mergeRecv();
            if (fromB1 != null && fromB2 != null) {
                try {
                    assertText(fromB1, "one", "conversation A");
                    assertText(fromB2, "two", "conversation B");
                } finally {
                    fromB1.release();
                    fromB2.release();
                }
                if (rejected == 0) {
                    throw new AssertionError("FAIL: the foreign conv was never rejected, so the "
                            + "isolation assertion above proved nothing");
                }
                System.out.println("[kernel] two conversations stayed apart, "
                        + rejected + " foreign datagram(s) rejected");
                return;
            }
            if (fromB1 != null) fromB1.release();
            if (fromB2 != null) fromB2.release();
        }
        throw new AssertionError("FAIL: the two conversations never both delivered");
    }

    /**
     * Upstream converts malformed input into exceptions at the session layer; at the kernel it is a
     * negative return. The channel treats an exception as fatal, so this is the behaviour the next
     * ticket depends on.
     */
    private static void junkIsRejectedWithoutThrowing() {
        Kcp kcp = new Kcp(CONV_A, new Wire());
        long now = System.currentTimeMillis();
        byte[][] junk = {
                {1, 2, 3},          // shorter than the 24-byte header
                new byte[24],       // header-sized, all zero: conv 0 belongs to nobody
                new byte[60],       // plausible size, zero body
        };
        for (byte[] payload : junk) {
            ByteBuf buf = Unpooled.wrappedBuffer(payload);
            try {
                int ret = kcp.input(buf, true, now);
                if (ret >= 0) {
                    throw new AssertionError("FAIL: junk of " + payload.length
                            + " bytes was accepted with " + ret);
                }
            } finally {
                buf.release();
            }
        }
        System.out.println("[kernel] " + junk.length + " junk datagrams rejected without throwing");
    }

    /**
     * The wire format itself: a standard 24-byte KCP header, with the payload immediately after it
     * and nothing appended. That second half is what proves the trimming (no FEC, no CRC32, no
     * private trailer) did not change what another implementation sees.
     *
     * <p>The check is shown to bite at the end: a copy with one header byte corrupted has to be
     * rejected, otherwise a validator that accepted everything would also look green here.
     */
    private static void headerLayoutIsStandard() {
        Wire wire = new Wire();
        Kcp kcp = new Kcp(CONV_A, wire);
        // Explicit, because the two bases disagree on defaults (window sizes, interval).
        kcp.nodelay(true, 10, 2, true);
        kcp.setSndWnd(1024);
        kcp.setRcvWnd(1024);
        kcp.setMtu(MTU);

        byte[] payload = new byte[3000];
        kcp.send(Unpooled.wrappedBuffer(payload));
        kcp.update(System.currentTimeMillis());

        int segments = (payload.length + MSS - 1) / MSS;
        int seen = 0;
        ByteBuf datagram;
        while ((datagram = wire.queue.poll()) != null) {
            try {
                assertStandardHeader(datagram, seen, segments - 1 - seen);
                seen++;
            } finally {
                datagram.release();
            }
        }
        if (seen != segments) {
            throw new AssertionError("FAIL: expected " + segments + " push datagrams for "
                    + payload.length + " bytes, saw " + seen);
        }

        // Now make sure the validator is not vacuous: corrupt one header byte and require a fail.
        Wire again = new Wire();
        Kcp second = new Kcp(CONV_A, again);
        second.nodelay(true, 10, 2, true);
        second.setSndWnd(1024);
        second.setRcvWnd(1024);
        second.setMtu(MTU);
        second.send(Unpooled.wrappedBuffer(payload));
        second.update(System.currentTimeMillis());
        ByteBuf corrupted = Unpooled.copiedBuffer(again.queue.peek());
        try {
            corrupted.setByte(corrupted.readerIndex() + 4, CMD_PUSH + 1);
            boolean rejected = false;
            try {
                assertStandardHeader(corrupted, 0, segments - 1);
            } catch (AssertionError expected) {
                rejected = true;
            }
            if (!rejected) {
                throw new AssertionError("FAIL: the header check accepted a corrupted datagram, so "
                        + "it would not catch a wire-format change either");
            }
        } finally {
            corrupted.release();
            ByteBuf leftover;
            while ((leftover = again.queue.poll()) != null) {
                leftover.release();
            }
        }
        System.out.println("[kernel] " + seen + " datagrams carry a standard 24-byte header"
                + " (conv@0 cmd@4 frg@5 wnd@6 ts@8 sn@12 una@16 len@20) and no trailer");
    }

    /**
     * Decodes one datagram the way any other KCP implementation would, and fails on anything that
     * is not the standard header.
     */
    private static void assertStandardHeader(ByteBuf datagram, int expectedSn, int expectedFrg) {
        int at = datagram.readerIndex();
        int total = datagram.readableBytes();
        if (total < HEADER_SIZE) {
            throw new AssertionError("FAIL: datagram shorter than the 24-byte header: " + total);
        }
        long conv = datagram.getUnsignedIntLE(at);
        int cmd = datagram.getUnsignedByte(at + 4);
        int frg = datagram.getUnsignedByte(at + 5);
        int wnd = datagram.getUnsignedShortLE(at + 6);
        long ts = datagram.getUnsignedIntLE(at + 8);
        long sn = datagram.getUnsignedIntLE(at + 12);
        long una = datagram.getUnsignedIntLE(at + 16);
        int payloadLen = datagram.getUnsignedShortLE(at + 20);

        assertField(conv == (CONV_A & 0xFFFFFFFFL), "conv@0", conv);
        assertField(cmd == CMD_PUSH, "cmd@4 (IKCP_CMD_PUSH)", cmd);
        assertField(frg == expectedFrg, "frg@5 (fragment index, the last one is 0)", frg);
        assertField(wnd > 0, "wnd@6 (free receive slots)", wnd);
        // The vendored kernel stamps segments with milliseconds since its own construction, not
        // with the caller's clock, so right after construction this has to be a small number --
        // an absolute epoch would not fit.
        assertField(ts < 10_000, "ts@8 (ms since the kernel's clock origin)", ts);
        assertField(sn == expectedSn, "sn@12 (segment number)", sn);
        assertField(expectedSn != 0 || una == 0, "una@16 (next expected segment)", una);
        assertField(payloadLen == total - HEADER_SIZE,
                "len@20 (payload length; nothing may follow the payload)", payloadLen);
    }

    private static void assertField(boolean condition, String field, long actual) {
        if (!condition) {
            throw new AssertionError("FAIL: not a standard KCP header at " + field + ", found " + actual);
        }
    }

    /** Feeds one side's datagrams into both receivers, returning how many the foreign one rejected. */
    private static int feedCross(Wire wire, Kcp own, Kcp foreign, long now) {
        int rejected = 0;
        ByteBuf buf;
        while ((buf = wire.queue.poll()) != null) {
            ByteBuf forForeign = buf.retainedDuplicate();
            try {
                own.input(buf, true, now);
            } finally {
                buf.release();
            }
            try {
                if (foreign.input(forForeign, true, now) < 0) {
                    rejected++;
                }
            } finally {
                forForeign.release();
            }
        }
        return rejected;
    }

    private static void drain(Wire wire, Kcp to, long now) {
        ByteBuf buf;
        while ((buf = wire.queue.poll()) != null) {
            try {
                to.input(buf, true, now);
            } finally {
                buf.release();
            }
        }
    }

    private static ByteBuf readUntilDelivered(Kcp a, Wire fromA, Kcp b, Wire fromB) {
        long now = System.currentTimeMillis();
        for (int step = 0; step < MAX_STEPS; step++) {
            now += STEP_MS;
            a.update(now);
            drain(fromA, b, now);
            b.update(now);
            drain(fromB, a, now);
            ByteBuf got = b.mergeRecv();
            if (got != null) {
                return got;
            }
        }
        return null;
    }

    private static void assertText(ByteBuf buf, String expected, String label) {
        byte[] actual = new byte[buf.readableBytes()];
        buf.getBytes(buf.readerIndex(), actual);
        String text = new String(actual, StandardCharsets.UTF_8);
        if (!expected.equals(text)) {
            throw new AssertionError("FAIL: " + label + " delivered " + text
                    + " but should have delivered " + expected);
        }
    }

    /** The only seam this test needs: a queue standing in for the UDP socket. */
    private static final class Wire implements KcpOutput {
        private final Deque<ByteBuf> queue = new ArrayDeque<>();

        @Override
        public void out(ByteBuf data, IKcp kcp) {
            queue.add(data);
        }
    }
}
