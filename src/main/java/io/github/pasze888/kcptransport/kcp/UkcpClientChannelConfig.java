/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpClientChannelConfig
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import java.net.StandardSocketOptions;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelConfig;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public interface UkcpClientChannelConfig extends ChannelConfig, UkcpChannelConfig {

    @Override
    UkcpClientChannelConfig setNodelay(boolean nodelay);

    @Override
    UkcpClientChannelConfig setInterval(int interval);

    @Override
    UkcpClientChannelConfig setFastResend(int resend);

    @Override
    UkcpClientChannelConfig setNocwnd(boolean nc);

    @Override
    UkcpClientChannelConfig setMtu(int mtu);

    @Override
    UkcpClientChannelConfig setRcvWnd(int rcvWnd);

    @Override
    UkcpClientChannelConfig setSndWnd(int sndWnd);

    @Override
    UkcpClientChannelConfig setStream(boolean stream);

    /**
     * Gets the {@link StandardSocketOptions#SO_RCVBUF} option.
     */
    int getUdpReceiveBufferSize();

    /**
     * Sets the {@link StandardSocketOptions#SO_RCVBUF} option.
     */
    UkcpClientChannelConfig setUdpReceiveBufferSize(int receiveBufferSize);

    /**
     * Gets the {@link StandardSocketOptions#SO_SNDBUF} option.
     */
    int getUdpSendBufferSize();

    /**
     * Sets the {@link StandardSocketOptions#SO_SNDBUF} option.
     */
    UkcpClientChannelConfig setUdpSendBufferSize(int sendBufferSize);

    /**
     * Gets the {@link StandardSocketOptions#IP_TOS} option.
     */
    int getUdpTrafficClass();

    /**
     * Sets the {@link StandardSocketOptions#IP_TOS} option.
     */
    UkcpClientChannelConfig setUdpTrafficClass(int trafficClass);

    /**
     * Gets the {@link StandardSocketOptions#SO_REUSEADDR} option.
     */
    boolean isReuseAddress();

    /**
     * Gets the {@link StandardSocketOptions#SO_REUSEADDR} option.
     */
    UkcpClientChannelConfig setReuseAddress(boolean reuseAddress);

    @Override
    @Deprecated
    UkcpClientChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead);

    @Override
    UkcpClientChannelConfig setWriteSpinCount(int writeSpinCount);

    @Override
    UkcpClientChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis);

    @Override
    UkcpClientChannelConfig setAllocator(ByteBufAllocator allocator);

    @Override
    UkcpClientChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator);

    @Override
    UkcpClientChannelConfig setAutoRead(boolean autoRead);

    @Override
    UkcpClientChannelConfig setAutoClose(boolean autoClose);

    @Override
    UkcpClientChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator);

    @Override
    UkcpClientChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark);

}