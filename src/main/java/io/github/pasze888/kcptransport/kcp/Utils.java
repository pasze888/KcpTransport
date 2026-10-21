/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), Utils
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;


import java.util.concurrent.TimeUnit;

import io.github.pasze888.kcptransport.kcp.internal.CodecOutputList;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
class Utils {

    static int milliSeconds() {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    }

    static int itimediff(int later, int earlier) {
        return later - earlier;
    }

    static void fireExceptionAndClose(Channel channel, Throwable t, boolean close) {
        channel.pipeline().fireExceptionCaught(t);
        if (channel.isActive()) {
            Channel.Unsafe unsafe = channel.unsafe();
            unsafe.close(unsafe.voidPromise());
        }
    }

    /**
     * Closes a channel without firing an exception.
     *
     * <p>KcpTransport: used for dead links. KCP reports a dead link when a segment has been
     * retransmitted {@code dead_link} times, which for UDP simply means the peer went away --
     * an expected end of session, not an error worth a stack trace.
     */
    static void closeQuietly(Channel channel) {
        if (channel.isActive()) {
            Channel.Unsafe unsafe = channel.unsafe();
            unsafe.close(unsafe.voidPromise());
        }
    }

    static void fireChannelRead(Channel ch, CodecOutputList<ByteBuf> bufList) {
        ChannelPipeline pipeline = ch.pipeline();
        int size = bufList.size();
        if (size <= 0) {
            return;
        }
        for (int i = 0; i < size; i++) {
            ByteBuf msg = bufList.getUnsafe(i);
            pipeline.fireChannelRead(msg);
        }
        pipeline.fireChannelReadComplete();
    }

}
