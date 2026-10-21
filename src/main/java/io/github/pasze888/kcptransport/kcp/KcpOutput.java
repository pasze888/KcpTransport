/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), KcpOutput
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.buffer.ByteBuf;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface KcpOutput {

    void out(ByteBuf data, Kcp kcp);

}
