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

    public static void main(String[] args) {
        roundTrip();
        twoConversationsDoNotCrossTalk();
        junkIsRejectedWithoutThrowing();
        System.out.println("[kernel] PASS: the vendored kernel round-trips a multi-segment payload, "
                + "keeps two conversations apart, and rejects junk without throwing");
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
