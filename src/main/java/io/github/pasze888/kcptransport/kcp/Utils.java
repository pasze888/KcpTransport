/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), Utils
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import io.netty.channel.Channel;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
class Utils {

    static void fireExceptionAndClose(Channel channel, Throwable t, boolean close) {
        channel.pipeline().fireExceptionCaught(t);
        if (close && channel.isActive()) {
            Channel.Unsafe unsafe = channel.unsafe();
            unsafe.close(unsafe.voidPromise());
        }
    }

}