/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), ChannelOptionHelper
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.bootstrap.Bootstrap;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class ChannelOptionHelper {

    public static Bootstrap nodelay(Bootstrap b, boolean nodelay, int interval, int fastResend, boolean nocwnd) {
        b.option(UkcpChannelOption.UKCP_NODELAY, nodelay)
                .option(UkcpChannelOption.UKCP_INTERVAL, interval)
                .option(UkcpChannelOption.UKCP_FAST_RESEND, fastResend)
                .option(UkcpChannelOption.UKCP_NOCWND, nocwnd);
        return b;
    }


}
