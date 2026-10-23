/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), DefaultUkcpClientChannelConfig
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_FAST_FLUSH;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_FAST_RESEND;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_INTERVAL;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_MTU;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_NOCWND;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_NODELAY;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_RCV_WND;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_SND_WND;
import static io.github.pasze888.kcptransport.kcp.UkcpChannelOption.UKCP_STREAM;
import static io.netty.channel.ChannelOption.IP_TOS;
import static io.netty.channel.ChannelOption.SO_RCVBUF;
import static io.netty.channel.ChannelOption.SO_REUSEADDR;
import static io.netty.channel.ChannelOption.SO_SNDBUF;

import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.Map;
import java.util.Objects;

import io.github.pasze888.kcptransport.kcp.base.KcpConfig;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;

/**
 * Per-session KCP settings for the client channel, backed by the vendored {@link KcpConfig} (see
 * {@link DefaultUkcpServerChildChannelConfig} for why the values live there), plus the UDP socket
 * options the channel owns.
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class DefaultUkcpClientChannelConfig extends DefaultChannelConfig implements UkcpClientChannelConfig {

    private final UkcpSessionConfig session;
    private final DatagramSocket javaSocket;

    public DefaultUkcpClientChannelConfig(UkcpClientChannel channel, int conv, DatagramSocket javaSocket) {
        super(channel, new FixedRecvByteBufAllocator(Consts.FIXED_RECV_BYTEBUF_ALLOCATE_SIZE));
        KcpConfig kcpConfig = new KcpConfig();
        kcpConfig.setConv(conv);
        this.session = new UkcpSessionConfig(kcpConfig);
        this.javaSocket = Objects.requireNonNull(javaSocket, "javaSocket");
    }

    /** The config the vendored session reads once, at construction. */
    UkcpSessionConfig session() {
        return session;
    }

    private KcpConfig kcp() {
        return session.getKcpConfig();
    }

    @Override
    @SuppressWarnings("deprecation")
    public Map<ChannelOption<?>, Object> getOptions() {
        return getOptions(super.getOptions(),
                UKCP_NODELAY, UKCP_INTERVAL, UKCP_FAST_RESEND, UKCP_NOCWND, UKCP_MTU,
                UKCP_RCV_WND, UKCP_SND_WND, UKCP_STREAM, UKCP_FAST_FLUSH,
                SO_RCVBUF, SO_SNDBUF, SO_REUSEADDR, IP_TOS);
    }

    @Override
    @SuppressWarnings({"unchecked", "deprecation"})
    public <T> T getOption(ChannelOption<T> option) {
        if (option == UKCP_NODELAY) {
            return (T) Boolean.valueOf(isNodelay());
        }
        if (option == UKCP_INTERVAL) {
            return (T) Integer.valueOf(getInterval());
        }
        if (option == UKCP_FAST_RESEND) {
            return (T) Integer.valueOf(getFastResend());
        }
        if (option == UKCP_NOCWND) {
            return (T) Boolean.valueOf(isNocwnd());
        }
        if (option == UKCP_MTU) {
            return (T) Integer.valueOf(getMtu());
        }
        if (option == UKCP_RCV_WND) {
            return (T) Integer.valueOf(getRcvWnd());
        }
        if (option == UKCP_SND_WND) {
            return (T) Integer.valueOf(getSndWnd());
        }
        if (option == UKCP_STREAM) {
            return (T) Boolean.valueOf(isStream());
        }
        if (option == UKCP_FAST_FLUSH) {
            return (T) Boolean.valueOf(isFastFlush());
        }
        if (option == SO_RCVBUF) {
            return (T) Integer.valueOf(getUdpReceiveBufferSize());
        }
        if (option == SO_SNDBUF) {
            return (T) Integer.valueOf(getUdpSendBufferSize());
        }
        if (option == SO_REUSEADDR) {
            return (T) Boolean.valueOf(isReuseAddress());
        }
        if (option == IP_TOS) {
            return (T) Integer.valueOf(getUdpTrafficClass());
        }
        return super.getOption(option);
    }

    @Override
    @SuppressWarnings("deprecation")
    public <T> boolean setOption(ChannelOption<T> option, T value) {
        validate(option, value);

        if (option == UKCP_NODELAY) {
            setNodelay((Boolean) value);
        } else if (option == UKCP_INTERVAL) {
            setInterval((Integer) value);
        } else if (option == UKCP_FAST_RESEND) {
            setFastResend((Integer) value);
        } else if (option == UKCP_NOCWND) {
            setNocwnd((Boolean) value);
        } else if (option == UKCP_MTU) {
            setMtu((Integer) value);
        } else if (option == UKCP_RCV_WND) {
            setRcvWnd((Integer) value);
        } else if (option == UKCP_SND_WND) {
            setSndWnd((Integer) value);
        } else if (option == UKCP_STREAM) {
            setStream((Boolean) value);
        } else if (option == UKCP_FAST_FLUSH) {
            setFastFlush((Boolean) value);
        } else if (option == SO_RCVBUF) {
            setUdpReceiveBufferSize((Integer) value);
        } else if (option == SO_SNDBUF) {
            setUdpSendBufferSize((Integer) value);
        } else if (option == SO_REUSEADDR) {
            setReuseAddress((Boolean) value);
        } else if (option == IP_TOS) {
            setUdpTrafficClass((Integer) value);
        } else {
            return super.setOption(option, value);
        }

        return true;
    }

    @Override
    public boolean isNodelay() {
        return kcp().isNodelay();
    }

    @Override
    public UkcpClientChannelConfig setNodelay(boolean nodelay) {
        kcp().nodelay(nodelay, kcp().getInterval(), kcp().getFastresend(), kcp().isNocwnd());
        return this;
    }

    @Override
    public int getInterval() {
        return kcp().getInterval();
    }

    @Override
    public UkcpClientChannelConfig setInterval(int interval) {
        kcp().nodelay(kcp().isNodelay(), interval, kcp().getFastresend(), kcp().isNocwnd());
        return this;
    }

    @Override
    public int getFastResend() {
        return kcp().getFastresend();
    }

    @Override
    public UkcpClientChannelConfig setFastResend(int fastResend) {
        kcp().nodelay(kcp().isNodelay(), kcp().getInterval(), fastResend, kcp().isNocwnd());
        return this;
    }

    @Override
    public boolean isNocwnd() {
        return kcp().isNocwnd();
    }

    @Override
    public UkcpClientChannelConfig setNocwnd(boolean nocwnd) {
        kcp().nodelay(kcp().isNodelay(), kcp().getInterval(), kcp().getFastresend(), nocwnd);
        return this;
    }

    @Override
    public int getMtu() {
        return kcp().getMtu();
    }

    @Override
    public UkcpClientChannelConfig setMtu(int mtu) {
        kcp().setMtu(mtu);
        return this;
    }

    @Override
    public int getRcvWnd() {
        return kcp().getRcvwnd();
    }

    @Override
    public UkcpClientChannelConfig setRcvWnd(int rcvWnd) {
        kcp().setRcvwnd(rcvWnd);
        return this;
    }

    @Override
    public int getSndWnd() {
        return kcp().getSndwnd();
    }

    @Override
    public UkcpClientChannelConfig setSndWnd(int sndWnd) {
        kcp().setSndwnd(sndWnd);
        return this;
    }

    @Override
    public boolean isStream() {
        return kcp().isStream();
    }

    @Override
    public UkcpClientChannelConfig setStream(boolean stream) {
        kcp().setStream(stream);
        return this;
    }

    @Override
    public boolean isFastFlush() {
        return session.isFastFlush();
    }

    @Override
    public UkcpChannelConfig setFastFlush(boolean fastFlush) {
        session.setFastFlush(fastFlush);
        return this;
    }

    @Override
    public int getUdpReceiveBufferSize() {
        try {
            return javaSocket.getReceiveBufferSize();
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public UkcpClientChannelConfig setUdpReceiveBufferSize(int receiveBufferSize) {
        try {
            javaSocket.setReceiveBufferSize(receiveBufferSize);
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
        return this;
    }

    @Override
    public int getUdpSendBufferSize() {
        try {
            return javaSocket.getSendBufferSize();
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public UkcpClientChannelConfig setUdpSendBufferSize(int sendBufferSize) {
        try {
            javaSocket.setSendBufferSize(sendBufferSize);
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
        return this;
    }

    @Override
    public int getUdpTrafficClass() {
        try {
            return javaSocket.getTrafficClass();
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public UkcpClientChannelConfig setUdpTrafficClass(int trafficClass) {
        try {
            javaSocket.setTrafficClass(trafficClass);
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
        return this;
    }

    @Override
    public boolean isReuseAddress() {
        try {
            return javaSocket.getReuseAddress();
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
    }

    @Override
    public UkcpClientChannelConfig setReuseAddress(boolean reuseAddress) {
        try {
            javaSocket.setReuseAddress(reuseAddress);
        } catch (SocketException e) {
            throw new ChannelException(e);
        }
        return this;
    }

    @Override
    public UkcpClientChannelConfig setAllocator(ByteBufAllocator allocator) {
        super.setAllocator(allocator);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setWriteSpinCount(int writeSpinCount) {
        super.setWriteSpinCount(writeSpinCount);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        super.setConnectTimeoutMillis(connectTimeoutMillis);
        return this;
    }

    @Override
    @Deprecated
    public UkcpClientChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) {
        super.setMaxMessagesPerRead(maxMessagesPerRead);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) {
        super.setRecvByteBufAllocator(allocator);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setAutoRead(boolean autoRead) {
        super.setAutoRead(autoRead);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setAutoClose(boolean autoClose) {
        super.setAutoClose(autoClose);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
        super.setWriteBufferHighWaterMark(writeBufferHighWaterMark);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
        super.setWriteBufferLowWaterMark(writeBufferLowWaterMark);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
        super.setWriteBufferWaterMark(writeBufferWaterMark);
        return this;
    }

    @Override
    public UkcpClientChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
        super.setMessageSizeEstimator(estimator);
        return this;
    }

}