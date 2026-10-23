/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpServerChildChannel
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import io.github.pasze888.kcptransport.kcp.base.IKcp;
import io.github.pasze888.kcptransport.kcp.base.KcpListener;
import io.github.pasze888.kcptransport.kcp.base.KcpOutput;
import io.github.pasze888.kcptransport.kcp.base.Ukcp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.IoEventLoop;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * One KCP session, presented as a Netty channel.
 *
 * <p>KcpTransport: the session underneath is java-kcp's {@code kcp.base.Ukcp} -- it owns the read
 * and write queues, the tasks that drain them, and the close handshake. This class is the shell
 * that turns its callbacks into channel events ({@link KcpListener}) and its datagrams into writes
 * on the parent channel ({@link KcpOutput}).
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public final class UkcpServerChildChannel extends AbstractChannel
        implements UkcpChannel, KcpListener, KcpOutput {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(UkcpServerChildChannel.class);

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';

    private final DefaultUkcpServerChildChannelConfig config;

    private final InetSocketAddress remoteAddress;

    /**
     * Built on the first datagram, not in the constructor: {@code kcp.base.Ukcp} copies its
     * {@code KcpConfig} once at construction, and {@code ServerBootstrapAcceptor} applies this
     * channel's child options only after the constructor returns.
     */
    private Ukcp ukcp;

    private boolean flushPending;

    private boolean closing;

    UkcpServerChildChannel(Channel parent, InetSocketAddress remoteAddress, int conv) {
        super(parent);
        this.remoteAddress = remoteAddress;
        this.config = new DefaultUkcpServerChildChannelConfig(this, conv);
    }

    /** The session, created on first use so every child option is already in effect. */
    Ukcp ukcp() {
        if (ukcp == null) {
            UkcpSessionConfig sessionConfig = config.session();
            // KcpTransport: the session queue is bounded at the point the old shell stopped
            // draining Netty's outbound buffer (its send-window check, times MTU), so a peer that
            // stops acking backs the write path up instead of growing a queue without limit.
            sessionConfig.setWriteBufferSize(2 * config.getSndWnd() * config.getMtu());
            ukcp = new Ukcp(this, this, InlineMessageExecutor.INSTANCE, sessionConfig, parent());
            if (log.isTraceEnabled()) {
                log.trace("Created session. remoteAddress={}, conv={}", remoteAddress, ukcp.getConv());
            }
        }
        return ukcp;
    }

    @Override
    public UkcpServerChannel parent() {
        return (UkcpServerChannel) super.parent();
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public UkcpChannelConfig config() {
        return config;
    }

    @Override
    public boolean isOpen() {
        return !closing;
    }

    @Override
    public boolean isActive() {
        return !closing;
    }

    @Override
    public UkcpServerChildUnsafe unsafe() {
        return (UkcpServerChildUnsafe) super.unsafe();
    }

    @Override
    protected UkcpServerChildUnsafe newUnsafe() {
        return new UkcpServerChildUnsafe();
    }

    /**
     * KcpTransport: this is a logical channel with no socket of its own, so it only needs a loop
     * that can drive a Netty 4.2 pipeline -- any {@link IoEventLoop}, not just the deprecated
     * {@code NioEventLoop} of the 4.1 compatibility path. See issue #11.
     */
    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof IoEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return parent().localAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doClose() throws Exception {
        closing = true;
        parent().removeChild(this);
        if (ukcp != null) {
            // Idempotent: internalClose() is a no-op once the session is inactive, which is the
            // case when the session itself asked for this close.
            ukcp.close();
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        // read operation be control by parent
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        Ukcp ukcp = ukcp();
        for (; ; ) {
            ByteBuf msg = (ByteBuf) in.current();
            if (msg == null) {
                flushPending = false;
                break;
            }
            // The session owns a queue of its own now: write() retains what it accepts, and says
            // false only when that queue is full. A refused message stays in Netty's outbound
            // buffer, and flushPending keeps retries off until the window opens again -- that is
            // what keeps a slow peer from turning into unbounded memory.
            if (!ukcp.write(msg)) {
                flushPending = true;
                break;
            }
            in.remove();
        }
    }

    @Override
    protected final Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            return msg;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    boolean isFlushPending() {
        return flushPending;
    }

    @Override
    public int conv() {
        return ukcp != null ? ukcp.getConv() : config.session().getKcpConfig().getConv();
    }

    @Override
    public UkcpServerChildChannel conv(int conv) {
        if (ukcp == null) {
            config.session().getKcpConfig().setConv(conv);
        } else {
            ukcp.setConv(conv);
        }
        return this;
    }

    // ---- KcpListener: what the session reports back -------------------------------------------

    @Override
    public void onConnected(Ukcp ukcp) {
        // Nothing to do here: the child channel was handed to the parent's pipeline when it was
        // created, and that is what registers it and fires channelActive.
    }

    @Override
    public void handleReceive(ByteBuf byteBuf, Ukcp ukcp) {
        // ReadTask releases its own reference as soon as we return, and the vanilla decoders take
        // over whatever they are given (cumulate, then release), so the pipeline needs a reference
        // of its own rather than a borrowed one.
        pipeline().fireChannelRead(byteBuf.retain());
    }

    @Override
    public void handleException(Throwable ex, Ukcp ukcp) {
        Utils.fireExceptionAndClose(this, ex, true);
    }

    @Override
    public void handleClose(Ukcp ukcp) {
        closeFromSession();
    }

    /** Ends the channel because the session ended (peer gone, timeout, or an explicit close). */
    private void closeFromSession() {
        if (closing) {
            return;
        }
        closing = true;
        parent().removeChild(this);
        Unsafe unsafe = unsafe();
        unsafe.close(unsafe.voidPromise());
    }

    // ---- KcpOutput: datagrams the session wants on the wire -----------------------------------

    @Override
    public void out(ByteBuf data, IKcp kcp) {
        // kcp.base's ownership contract: the output takes the buffer, and whoever consumes it
        // releases it. UkcpPacket does that on deallocate, exactly as before.
        //
        // KcpTransport: written through the parent's public outbound API, not through its Unsafe.
        // A child and its parent can sit on different event loops (the smokes split boss from
        // worker, and issue #17 will split per session on purpose), and Unsafe.write() mutates the
        // parent's outbound buffer without any locking -- two loops doing that at once can leave
        // the buffer in the state Netty refuses to close ("close() must be invoked after all
        // flushed writes are handled"). Channel.writeAndFlush() is the thread-safe entry point:
        // inline when already on the parent's loop, dispatched to it otherwise.
        UkcpServerChannel parent = parent();
        parent.writeAndFlush(UkcpPacket.newInstance(data, remoteAddress), parent.voidPromise());
    }

    final class UkcpServerChildUnsafe extends AbstractUnsafe {

        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            safeSetFailure(promise, new UnsupportedOperationException());
        }

        @Override
        protected void flush0() {
            if (isFlushPending()) {
                return;
            }
            super.flush0();
        }

        /** Retries the outbound buffer once the session has room again (the parent's timer). */
        void forceFlush() {
            super.flush0();
        }
    }

}