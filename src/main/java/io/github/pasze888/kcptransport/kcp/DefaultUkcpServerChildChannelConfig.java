/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), DefaultUkcpServerChildChannelConfig
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

import java.util.Map;

import io.github.pasze888.kcptransport.kcp.base.KcpConfig;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.MessageSizeEstimator;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.WriteBufferWaterMark;

/**
 * Per-session KCP settings for a server child channel, backed by the vendored {@link KcpConfig}.
 *
 * <p>KcpTransport: the values live in a {@link KcpConfig} rather than being pushed into a live
 * {@code Ukcp} as options arrive, because {@code kcp.base.Ukcp} copies the config once, when it is
 * constructed. The child builds its session on its event loop at registration -- after
 * {@code ServerBootstrapAcceptor} has applied these child options -- so every option set here is
 * in effect from the session's first packet.
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public class DefaultUkcpServerChildChannelConfig extends DefaultChannelConfig implements UkcpChannelConfig {

    private final UkcpSessionConfig session;

    public DefaultUkcpServerChildChannelConfig(UkcpServerChildChannel channel, int conv) {
        super(channel, new FixedRecvByteBufAllocator(Consts.FIXED_RECV_BYTEBUF_ALLOCATE_SIZE));
        KcpConfig kcpConfig = new KcpConfig();
        // Adopted from the client's first datagram (KcpTransport server shell): the vendored
        // kernel has no autoSetConv and rejects a datagram whose conv is not its own.
        kcpConfig.setConv(conv);
        this.session = new UkcpSessionConfig(kcpConfig);
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
                UKCP_RCV_WND, UKCP_SND_WND, UKCP_STREAM, UKCP_FAST_FLUSH);
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
    public UkcpChannelConfig setNodelay(boolean nodelay) {
        kcp().nodelay(nodelay, kcp().getInterval(), kcp().getFastresend(), kcp().isNocwnd());
        return this;
    }

    @Override
    public int getInterval() {
        return kcp().getInterval();
    }

    @Override
    public UkcpChannelConfig setInterval(int interval) {
        kcp().nodelay(kcp().isNodelay(), interval, kcp().getFastresend(), kcp().isNocwnd());
        return this;
    }

    @Override
    public int getFastResend() {
        return kcp().getFastresend();
    }

    @Override
    public UkcpChannelConfig setFastResend(int fastResend) {
        kcp().nodelay(kcp().isNodelay(), kcp().getInterval(), fastResend, kcp().isNocwnd());
        return this;
    }

    @Override
    public boolean isNocwnd() {
        return kcp().isNocwnd();
    }

    @Override
    public UkcpChannelConfig setNocwnd(boolean nocwnd) {
        kcp().nodelay(kcp().isNodelay(), kcp().getInterval(), kcp().getFastresend(), nocwnd);
        return this;
    }

    @Override
    public int getMtu() {
        return kcp().getMtu();
    }

    @Override
    public UkcpChannelConfig setMtu(int mtu) {
        kcp().setMtu(mtu);
        return this;
    }

    @Override
    public int getRcvWnd() {
        return kcp().getRcvwnd();
    }

    @Override
    public UkcpChannelConfig setRcvWnd(int rcvWnd) {
        kcp().setRcvwnd(rcvWnd);
        return this;
    }

    @Override
    public int getSndWnd() {
        return kcp().getSndwnd();
    }

    @Override
    public UkcpChannelConfig setSndWnd(int sndWnd) {
        kcp().setSndwnd(sndWnd);
        return this;
    }

    @Override
    public boolean isStream() {
        return kcp().isStream();
    }

    @Override
    public UkcpChannelConfig setStream(boolean stream) {
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
    public UkcpChannelConfig setAllocator(ByteBufAllocator allocator) {
        super.setAllocator(allocator);
        return this;
    }

    @Override
    public UkcpChannelConfig setWriteSpinCount(int writeSpinCount) {
        super.setWriteSpinCount(writeSpinCount);
        return this;
    }

    @Override
    public UkcpChannelConfig setConnectTimeoutMillis(int connectTimeoutMillis) {
        super.setConnectTimeoutMillis(connectTimeoutMillis);
        return this;
    }

    @Override
    @Deprecated
    public UkcpChannelConfig setMaxMessagesPerRead(int maxMessagesPerRead) {
        super.setMaxMessagesPerRead(maxMessagesPerRead);
        return this;
    }

    @Override
    public UkcpChannelConfig setRecvByteBufAllocator(RecvByteBufAllocator allocator) {
        super.setRecvByteBufAllocator(allocator);
        return this;
    }

    @Override
    public UkcpChannelConfig setAutoRead(boolean autoRead) {
        super.setAutoRead(autoRead);
        return this;
    }

    @Override
    public UkcpChannelConfig setAutoClose(boolean autoClose) {
        super.setAutoClose(autoClose);
        return this;
    }

    @Override
    public UkcpChannelConfig setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
        super.setWriteBufferHighWaterMark(writeBufferHighWaterMark);
        return this;
    }

    @Override
    public UkcpChannelConfig setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
        super.setWriteBufferLowWaterMark(writeBufferLowWaterMark);
        return this;
    }

    @Override
    public UkcpChannelConfig setWriteBufferWaterMark(WriteBufferWaterMark writeBufferWaterMark) {
        super.setWriteBufferWaterMark(writeBufferWaterMark);
        return this;
    }

    @Override
    public UkcpChannelConfig setMessageSizeEstimator(MessageSizeEstimator estimator) {
        super.setMessageSizeEstimator(estimator);
        return this;
    }

}