package smoke;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.util.ArrayList;
import java.util.List;

/**
 * One-off equivalence probe: the vendored java-kcp kernel must emit byte-identical datagrams to
 * the kcp-netty kernel it replaces, given identical input. This is the only thing that can prove
 * the trimming did not change the wire format (FEC and CRC32 removed, reserved header stays 0,
 * SNMP counters dropped, jctools replaced by Netty queues).
 *
 * <p><b>The one expected difference is {@code ts} (header bytes 8..11), and it is a clock origin,
 * not a format change.</b> The vendored kernel runs on a monotonic-ish clock of its own: it takes
 * {@code startTicks = System.currentTimeMillis()} when constructed and its {@code currentMs(now)}
 * maps every timestamp to {@code now - startTicks} before writing it out as a uint32. The
 * kcp-netty kernel it replaces writes the caller's timestamp through unchanged (which is why the
 * old datagram shows the literal {@code ts=1000} fed in below). Both ends use the same kernel
 * after issue #14, so the two clocks never meet on the wire; every other header field and the
 * whole payload must still match byte for byte, and that is what this probe asserts.
 *
 * <p>Lives and dies with the old base: delete this class when #16 removes kcp-netty.
 */
public final class KernelByteEquivalence {

    private static final int CONV = 0x5eed;

    // Explicit, because the two bases disagree on defaults: the old one ships IKCP_WND_RCV = 128,
    // the new one 32. Anything left to defaults would make the comparison meaningless.
    private static final int SND_WND = 1024;
    private static final int RCV_WND = 1024;
    private static final int MTU = 1400;
    private static final int INTERVAL = 10;
    private static final int FAST_RESEND = 2;

    private static final long[] TICKS = {1_000L, 1_010L, 1_020L, 1_030L, 1_100L, 2_000L};

    /**
     * The two kernels only diverge on {@code ts}, so the rest of the 24-byte header is compared
     * verbatim: bytes 0..7 ({@code conv, cmd, frg, wnd}) and 12..23 ({@code sn, una, len}).
     */
    private static final int TS_OFFSET = 8;
    private static final int TS_END = 12;

    public static void main(String[] args) {
        List<ByteBuf> oldOut = new ArrayList<>();
        List<ByteBuf> newOut = new ArrayList<>();

        io.github.pasze888.kcptransport.kcp.Kcp oldKcp =
                new io.github.pasze888.kcptransport.kcp.Kcp(CONV, (data, kcp) -> oldOut.add(data));
        // The vendored kernel reads its clock origin here, so bracket the construction and later
        // check that the ts it writes really is that origin and not some other constant.
        long beforeConstruction = System.currentTimeMillis();
        io.github.pasze888.kcptransport.kcp.base.Kcp newKcp =
                new io.github.pasze888.kcptransport.kcp.base.Kcp(CONV, (data, kcp) -> newOut.add(data));
        long afterConstruction = System.currentTimeMillis();

        configureOld(oldKcp);
        configureNew(newKcp);

        // Three segments worth: covers the 24-byte header plus a fragmented payload.
        byte[] payload = new byte[3000];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 17 + 3);
        }
        sendBoth(oldKcp, newKcp, payload);

        for (long tick : TICKS) {
            oldKcp.update((int) tick);
            newKcp.update(tick);
        }

        System.out.println("[equiv] old datagrams = " + oldOut.size() + ", new = " + newOut.size());
        if (oldOut.isEmpty()) {
            throw new AssertionError("FAIL: neither kernel emitted anything, the probe proves nothing");
        }
        if (oldOut.size() != newOut.size()) {
            throw new AssertionError("FAIL: datagram count differs: old " + oldOut.size()
                    + " vs new " + newOut.size());
        }

        long tsOffset = -1;
        for (int i = 0; i < oldOut.size(); i++) {
            ByteBuf o = oldOut.get(i);
            ByteBuf n = newOut.get(i);
            if (o.readableBytes() != n.readableBytes()) {
                throw new AssertionError("FAIL: datagram #" + i + " length differs\n  old " + describe(o)
                        + "\n  new " + describe(n));
            }
            assertEverythingButTsEquals(i, o, n);

            long oldTs = o.getUnsignedIntLE(o.readerIndex() + TS_OFFSET);
            long newTs = n.getUnsignedIntLE(n.readerIndex() + TS_OFFSET);
            // startTicks mod 2^32 is what separates the two clocks; it must be the same constant
            // for every datagram of one run, otherwise the new side is not using a single origin.
            long offset = (oldTs - newTs) & 0xFFFFFFFFL;
            if (tsOffset == -1) {
                tsOffset = offset;
            } else if (offset != tsOffset) {
                throw new AssertionError("FAIL: datagram #" + i + " moved its ts origin: " + offset
                        + " vs " + tsOffset + "\n  old " + describe(o) + "\n  new " + describe(n));
            }
            System.out.println("[equiv] datagram #" + i + " identical apart from ts: " + describe(o)
                    + " (new ts=" + newTs + ")");
        }

        // tsOffset is the vendored kernel's startTicks modulo 2^32. It has to be the wall clock
        // reading taken while the kernel was constructed, otherwise some other constant was
        // subtracted and the "clock origin" explanation is wrong.
        long constructionSpan = afterConstruction - beforeConstruction;
        long skew = ((afterConstruction & 0xFFFFFFFFL) - tsOffset) & 0xFFFFFFFFL;
        if (skew > constructionSpan) {
            throw new AssertionError("FAIL: ts origin " + tsOffset
                    + " is not the currentTimeMillis() read around construction (window "
                    + (beforeConstruction & 0xFFFFFFFFL) + ".." + (afterConstruction & 0xFFFFFFFFL) + ")");
        }

        for (ByteBuf b : oldOut) b.release();
        for (ByteBuf b : newOut) b.release();
        System.out.println("[equiv] PASS: " + oldOut.size() + " datagrams agree byte for byte outside"
                + " ts; ts differs by a constant startTicks mod 2^32 = " + tsOffset
                + " (clock origin, not wire format)");
    }

    /** Compares every byte except the uint32 ts field at offset 8. */
    private static void assertEverythingButTsEquals(int index, ByteBuf o, ByteBuf n) {
        int oi = o.readerIndex();
        int ni = n.readerIndex();
        int len = o.readableBytes();
        for (int i = 0; i < len; i++) {
            if (i >= TS_OFFSET && i < TS_END) {
                continue;
            }
            if (o.getByte(oi + i) != n.getByte(ni + i)) {
                throw new AssertionError("FAIL: datagram #" + index + " differs at byte " + i
                        + "\n  old " + describe(o) + "\n  new " + describe(n));
            }
        }
    }

    private static void sendBoth(Object oldKcp, Object newKcp, byte[] payload) {
        io.github.pasze888.kcptransport.kcp.Kcp o = (io.github.pasze888.kcptransport.kcp.Kcp) oldKcp;
        io.github.pasze888.kcptransport.kcp.base.Kcp n =
                (io.github.pasze888.kcptransport.kcp.base.Kcp) newKcp;
        ByteBuf oldBuf = Unpooled.wrappedBuffer(payload);
        ByteBuf newBuf = Unpooled.wrappedBuffer(payload);
        try {
            o.send(oldBuf);
            n.send(newBuf);
        } finally {
            oldBuf.release();
            newBuf.release();
        }
    }

    private static void configureOld(io.github.pasze888.kcptransport.kcp.Kcp kcp) {
        kcp.nodelay(true, INTERVAL, FAST_RESEND, true);
        kcp.setSndWnd(SND_WND);
        kcp.setRcvWnd(RCV_WND);
        kcp.setMtu(MTU);
        kcp.setStream(false);
    }

    private static void configureNew(io.github.pasze888.kcptransport.kcp.base.Kcp kcp) {
        kcp.nodelay(true, INTERVAL, FAST_RESEND, true);
        kcp.setSndWnd(SND_WND);
        kcp.setRcvWnd(RCV_WND);
        kcp.setMtu(MTU);
        kcp.setStream(false);
        kcp.setAckNoDelay(false);
        kcp.setAckMaskSize(0);
    }

    /** Decodes the standard 24-byte KCP header so a mismatch names the field that moved. */
    private static String describe(ByteBuf buf) {
        int at = buf.readerIndex();
        int len = buf.readableBytes();
        if (len < 24) {
            return len + " bytes (shorter than a KCP header)";
        }
        return "len=" + len
                + " conv=" + Integer.toHexString(buf.getIntLE(at))
                + " cmd=" + buf.getUnsignedByte(at + 4)
                + " frg=" + buf.getUnsignedByte(at + 5)
                + " wnd=" + buf.getUnsignedShortLE(at + 6)
                + " ts=" + buf.getUnsignedIntLE(at + 8)
                + " sn=" + buf.getUnsignedIntLE(at + 12)
                + " una=" + buf.getUnsignedIntLE(at + 16)
                + " hlen=" + buf.getUnsignedShortLE(at + 20)
                + " payload=" + (len - 24);
    }
}
