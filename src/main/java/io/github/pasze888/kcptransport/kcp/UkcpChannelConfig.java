/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpChannelConfig
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.channel.ChannelConfig;

/**
 * The KCP knobs a channel exposes: one getter/setter pair per field of the vendored
 * {@link io.github.pasze888.kcptransport.kcp.base.KcpConfig}.
 *
 * <p>KcpTransport: the kcp-netty-only knobs went away with that base. java-kcp has no
 * {@code deadLink} (its check is commented out upstream), no {@code minRto} setter, no
 * {@code fastLimit}, no {@code autoSetConv} (the server adopts the session number from the first
 * datagram itself) and no {@code mergeSegmentBuf} (its session layer always merges in message
 * mode).
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface UkcpChannelConfig extends ChannelConfig {

    boolean isNodelay();

    UkcpChannelConfig setNodelay(boolean nodelay);

    int getInterval();

    UkcpChannelConfig setInterval(int interval);

    int getFastResend();

    UkcpChannelConfig setFastResend(int fastResend);

    boolean isNocwnd();

    UkcpChannelConfig setNocwnd(boolean nc);

    int getMtu();

    UkcpChannelConfig setMtu(int mtu);

    int getRcvWnd();

    UkcpChannelConfig setRcvWnd(int rcvWnd);

    int getSndWnd();

    UkcpChannelConfig setSndWnd(int sndWnd);

    boolean isStream();

    UkcpChannelConfig setStream(boolean stream);

    boolean isFastFlush();

    UkcpChannelConfig setFastFlush(boolean fastFlush);

}