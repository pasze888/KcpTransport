/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpChannelConfig
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.channel.ChannelConfig;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface UkcpChannelConfig extends ChannelConfig {

    boolean isNodelay();

    UkcpChannelConfig setNodelay(boolean nodelay);

    int getInterval();

    UkcpChannelConfig setInterval(int interval);

    int getFastResend();

    UkcpChannelConfig setFastResend(int fastResend);

    int getFastLimit();

    UkcpChannelConfig setFastLimit(int fastLimit);

    boolean isNocwnd();

    UkcpChannelConfig setNocwnd(boolean nc);

    int getMinRto();

    UkcpChannelConfig setMinRto(int minRto);

    int getMtu();

    UkcpChannelConfig setMtu(int mtu);

    int getRcvWnd();

    UkcpChannelConfig setRcvWnd(int rcvWnd);

    int getSndWnd();

    UkcpChannelConfig setSndWnd(int sndWnd);

    boolean isStream();

    UkcpChannelConfig setStream(boolean stream);

    int getDeadLink();

    UkcpChannelConfig setDeadLink(int deadLink);

    boolean isAutoSetConv();

    UkcpChannelConfig setAutoSetConv(boolean autoSetConv);

    boolean isFastFlush();

    UkcpChannelConfig setFastFlush(boolean fastFlush);

    boolean isMergeSegmentBuf();

    UkcpChannelConfig setMergeSegmentBuf(boolean mergeSegmentBuf);

}
