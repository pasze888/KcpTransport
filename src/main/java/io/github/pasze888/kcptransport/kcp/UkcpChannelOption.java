/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpChannelOption
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.channel.ChannelOption;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public final class UkcpChannelOption<T> extends ChannelOption<T> {

    public static final ChannelOption<Boolean> UKCP_NODELAY =
            valueOf(UkcpChannelOption.class, "UKCP_NODELAY");

    public static final ChannelOption<Integer> UKCP_INTERVAL =
            valueOf(UkcpChannelOption.class, "UKCP_INTERVAL");

    public static final ChannelOption<Integer> UKCP_FAST_RESEND =
            valueOf(UkcpChannelOption.class, "UKCP_FAST_RESEND");

    public static final ChannelOption<Integer> UKCP_FAST_LIMIT =
            valueOf(UkcpChannelOption.class, "UKCP_FAST_LIMIT");

    public static final ChannelOption<Boolean> UKCP_NOCWND =
            valueOf(UkcpChannelOption.class, "UKCP_NOCWND");

    public static final ChannelOption<Integer> UKCP_MIN_RTO =
            valueOf(UkcpChannelOption.class, "UKCP_MIN_RTO");

    public static final ChannelOption<Integer> UKCP_MTU =
            valueOf(UkcpChannelOption.class, "UKCP_MTU");

    public static final ChannelOption<Integer> UKCP_RCV_WND =
            valueOf(UkcpChannelOption.class, "UKCP_RCV_WND");

    public static final ChannelOption<Integer> UKCP_SND_WND =
            valueOf(UkcpChannelOption.class, "UKCP_SND_WND");

    public static final ChannelOption<Boolean> UKCP_STREAM =
            valueOf(UkcpChannelOption.class, "UKCP_STREAM");

    public static final ChannelOption<Integer> UKCP_DEAD_LINK =
            valueOf(UkcpChannelOption.class, "UKCP_DEAD_LINK");

    public static final ChannelOption<Boolean> UKCP_AUTO_SET_CONV =
            valueOf(UkcpChannelOption.class, "UKCP_AUTO_SET_CONV");

    public static final ChannelOption<Boolean> UKCP_FAST_FLUSH =
            valueOf(UkcpChannelOption.class, "UKCP_FAST_FLUSH");

    public static final ChannelOption<Boolean> UKCP_MERGE_SEGMENT_BUF =
            valueOf(UkcpChannelOption.class, "UKCP_MERGE_SEGMENT_BUF");

    @SuppressWarnings("deprecation")
    private UkcpChannelOption() {
        super(null);
    }

}
