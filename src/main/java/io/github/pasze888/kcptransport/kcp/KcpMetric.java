/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), KcpMetric
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class KcpMetric {

    private Kcp kcp;

    private int maxSegXmit;

    public KcpMetric(Kcp kcp) {
        this.kcp = kcp;
    }

    public int srtt() {
        return kcp.getSrtt();
    }

    public int rttvar() {
        return kcp.getRttvar();
    }

    public int rto() {
        return kcp.getRto();
    }

    public long sndNxt() {
        return kcp.getSndNxt();
    }

    public long sndUna() {
        return kcp.getSndUna();
    }

    public long rcvNxt() {
        return kcp.getRcvNxt();
    }

    public int cwnd() {
        return kcp.getCwnd();
    }

    public int xmit() {
        return kcp.getXmit();
    }

    public int maxSegXmit() {
        return maxSegXmit;
    }

    public void maxSegXmit(int maxSegXmit) {
        this.maxSegXmit = maxSegXmit;
    }

    @Override
    public String toString() {
        return "KcpMetric(" +
                "kcp=" + kcp +
                ", srtt=" + srtt() +
                ", rttvar=" + rttvar() +
                ", rto=" + rto() +
                ", sndNxt=" + sndNxt() +
                ", sndUna=" + sndUna() +
                ", rcvNxt=" + rcvNxt() +
                ", cwnd=" + cwnd() +
                ", xmit=" + xmit() +
                ", maxSegXmit=" + maxSegXmit +
                ')';
    }

}
