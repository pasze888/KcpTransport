/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpChannel
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import java.net.InetSocketAddress;

import io.netty.channel.Channel;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface UkcpChannel extends Channel {

    int conv();

    @Override
    UkcpChannelConfig config();

    UkcpChannel conv(int conv);

    @Override
    InetSocketAddress localAddress();

    @Override
    InetSocketAddress remoteAddress();

}
