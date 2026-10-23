/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), KcpConfig.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

/**
 * The underlying KCP protocol configuration.
 * <p>
 * Created in 2026/3/27
 *
 * @author <a href="https://github.com/tzfun/">tzfun</a>
 */
public class KcpConfig {
    /**
     * Conversation ID of this KCP instance.
     */
    protected int conv;
    /**
     * Enable nodelay mode. When {@link true}, KCP disables the wait-to-send delay and sends packets
     * as soon as possible, reducing latency.
     */
    protected boolean nodelay;
    /**
     * Internal update interval in milliseconds. Lower values reduce latency but increase CPU
     * usage. Typical range: 10–100 ms.
     */
    protected int interval = Kcp.IKCP_INTERVAL;
    /**
     * Fast retransmit trigger count. When an out-of-order ACK is received this many times,
     * KCP immediately retransmits the packet without waiting for a timeout. Set to 0 to disable
     * fast retransmit.
     */
    protected int fastresend;
    /**
     * Disable congestion control. When {@link true}, KCP ignores the congestion window and sends at
     * full speed (uses `nocwnd` mode). Useful for low-latency scenarios where bandwidth is not a concern.
     */
    protected boolean nocwnd;
    /**
     * Send window size (number of packets). Larger values allow higher throughput but consume more memory.
     * Default: {@link Kcp#IKCP_WND_SND}
     */
    protected int sndwnd = Kcp.IKCP_WND_SND;
    /**
     * Receive window size (number of packets). Should generally be >= `snd_wnd`. Larger values allow
     * higher throughput. Default: {@link Kcp#IKCP_WND_RCV}.
     */
    protected int rcvwnd = Kcp.IKCP_WND_RCV;
    /**
     * Enable nodelay mode. When {@link true}, KCP disables the wait-to-send delay and sends
     * packets as soon as possible, reducing latency. Default: {@link Kcp#IKCP_MTU_DEF}
     */
    protected int mtu = Kcp.IKCP_MTU_DEF;

    /**
     * TODO 可能有bug还未测试
     * <p>
     * Enable stream mode. When {@link true}, KCP operates like a byte stream (similar to TCP),
     * merging small packets and splitting large ones. When {@link false}(default), KCP preserves
     * message boundaries.
     */
    protected boolean stream;
    /**
     * 收到包立刻回传ack包
     */
    protected boolean ackNoDelay = false;
    /**
     * 增加ack包回复成功率 填 /8/16/32
     */
    protected int ackMaskSize = 0;

    public void nodelay(boolean nodelay, int interval, int resend, boolean nc) {
        this.nodelay = nodelay;
        this.interval = interval;
        this.fastresend = resend;
        this.nocwnd = nc;
    }

    public void setConv(int conv) {
        this.conv = conv;
    }

    public void setSndwnd(int sndwnd) {
        this.sndwnd = sndwnd;
    }

    public void setRcvwnd(int rcvwnd) {
        this.rcvwnd = rcvwnd;
    }

    public void setMtu(int mtu) {
        this.mtu = mtu;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public void setAckNoDelay(boolean ackNoDelay) {
        this.ackNoDelay = ackNoDelay;
    }

    public void setAckMaskSize(int ackMaskSize) {
        this.ackMaskSize = ackMaskSize;
    }

    public int getConv() {
        return conv;
    }

    public boolean isNodelay() {
        return nodelay;
    }

    public int getInterval() {
        return interval;
    }

    public int getFastresend() {
        return fastresend;
    }

    public boolean isNocwnd() {
        return nocwnd;
    }

    public int getSndwnd() {
        return sndwnd;
    }

    public int getRcvwnd() {
        return rcvwnd;
    }

    public int getMtu() {
        return mtu;
    }

    public boolean isStream() {
        return stream;
    }

    public boolean isAckNoDelay() {
        return ackNoDelay;
    }

    public int getAckMaskSize() {
        return ackMaskSize;
    }
}
