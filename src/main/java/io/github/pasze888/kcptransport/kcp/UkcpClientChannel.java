/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpClientChannel
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import io.github.pasze888.kcptransport.kcp.base.IChannelManager;
import io.github.pasze888.kcptransport.kcp.base.IKcp;
import io.github.pasze888.kcptransport.kcp.base.KcpListener;
import io.github.pasze888.kcptransport.kcp.base.KcpOutput;
import io.github.pasze888.kcptransport.kcp.base.Ukcp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.IoEventLoop;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * The client end of a KCP connection: a channel that speaks the vanilla byte stream on top and
 * java-kcp's session on the bottom.
 *
 * <p>KcpTransport: the session is {@code kcp.base.Ukcp}, so this class is a shell that owns the
 * UDP socket (through {@link UkcpClientUdpChannel}), feeds datagrams to the session, and turns its
 * callbacks into channel events. The session's timestamp is what {@link #run()} schedules on, the
 * same way upstream's per-session timer does.
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public final class UkcpClientChannel extends AbstractChannel
        implements UkcpChannel, Runnable, KcpListener, KcpOutput, IChannelManager {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(UkcpClientChannel.class);

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';

    private final DefaultUkcpClientChannelConfig config;

    private final UkcpClientUdpChannel udpChannel;

    /**
     * Built on first use after the bootstrap applied the channel options, not in the constructor:
     * {@code kcp.base.Ukcp} copies its {@code KcpConfig} once at construction, and options are
     * applied after the channel exists.
     */
    private Ukcp ukcp;

    private boolean flushPending;

    private boolean closing;

    public UkcpClientChannel() {
        super(null);
        this.udpChannel = new UkcpClientUdpChannel(this);
        this.config = new DefaultUkcpClientChannelConfig(this, nextConv(), udpChannel.javaChannel().socket());
    }

    /**
     * KcpTransport: upstream left the conv at 0 and assigned it to the caller, so every client
     * shared conv 0. Give each channel its own non-zero conv; {@link #conv(int)} still overrides it
     * (e.g. to resume a session with the previous conv on reconnect), as long as it is set before
     * the session is built.
     */
    private static int nextConv() {
        int conv;
        do {
            conv = ThreadLocalRandom.current().nextInt();
        } while (conv == 0);
        return conv;
    }

    /** The session, created on first use so every channel option is already in effect. */
    Ukcp ukcp() {
        if (ukcp == null) {
            UkcpSessionConfig sessionConfig = config.session();
            // KcpTransport: same bound as the server child channel -- refuse writes once the
            // session queue is two send windows deep, so a peer that stops acking backs Netty's
            // outbound buffer up instead of growing a queue without limit.
            sessionConfig.setWriteBufferSize(2 * config.getSndWnd() * config.getMtu());
            ukcp = new Ukcp(this, this, InlineMessageExecutor.INSTANCE, sessionConfig, this);
        }
        return ukcp;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public UkcpClientChannelConfig config() {
        return config;
    }

    @Override
    public UkcpClientUnsafe unsafe() {
        return (UkcpClientUnsafe) super.unsafe();
    }

    @Override
    protected UkcpClientUnsafe newUnsafe() {
        return new UkcpClientUnsafe();
    }

    @Override
    public boolean isOpen() {
        return udpChannel.isOpen();
    }

    @Override
    public boolean isActive() {
        return udpChannel.isActive();
    }

    /**
     * KcpTransport: the KCP channels run on Netty 4.2's IoHandle model, so any {@link IoEventLoop}
     * will do; the old {@code NioEventLoop} requirement pinned them to the deprecated 4.1 compat
     * path. See issue #11.
     */
    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof IoEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return udpChannel.localAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return udpChannel.remoteAddress();
    }

    @Override
    protected void doRegister() throws Exception {
        eventLoop().register(udpChannel).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    forceClose(future.cause());
                }
            }
        });
    }

    private void forceClose(Throwable t) {
        unsafe().closeForcibly();
        ((ChannelPromise) closeFuture()).trySuccess();
        log.warn("Failed to register an UkcpClientUdpChannel: {}", this, t);
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        udpChannel.doBind(localAddress);
    }

    private boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        boolean connected = udpChannel.doConnect(remoteAddress, localAddress);
        if (connected) {
            scheduleUpdate();
        }
        return connected;
    }

    @Override
    protected void doDisconnect() throws Exception {
        udpChannel.doDisconnect();
    }

    @Override
    protected void doClose() throws Exception {
        closing = true;
        if (ukcp != null) {
            // Idempotent: a no-op once the session is already inactive, which is the case when the
            // session itself asked for this close.
            ukcp.close();
        }
        udpChannel.closeFromOwner();
    }

    @Override
    protected void doBeginRead() throws Exception {
        udpChannel.doBeginRead();
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
            // write() retains what it accepts and answers false only when the session queue is
            // full; the message then stays in Netty's outbound buffer until the window opens.
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

    public boolean isFlushPending() {
        return flushPending;
    }

    @Override
    public int conv() {
        return ukcp != null ? ukcp.getConv() : config.session().getKcpConfig().getConv();
    }

    @Override
    public UkcpClientChannel conv(int conv) {
        if (ukcp == null) {
            config.session().getKcpConfig().setConv(conv);
        } else {
            ukcp.setConv(conv);
        }
        return this;
    }

    /** Feeds one datagram read off the socket to the session, which takes the buffer over. */
    void kcpRead(ByteBuf byteBuf) {
        if (ukcp == null || !ukcp.isActive()) {
            byteBuf.release();
            return;
        }
        ukcp.read(byteBuf);
    }

    boolean kcpIsActive() {
        return ukcp == null || ukcp.isActive();
    }

    private void scheduleUpdate() {
        if (closing) {
            return;
        }
        eventLoop().execute(this);
    }

    /**
     * Flushes the session when its own timestamp comes due, then reschedules for the delay the
     * flush asked for. This is upstream's {@code ScheduleTask} logic without the shared timer: one
     * channel, one timer, and (until issue #17) one event loop.
     */
    @Override
    public void run() {
        if (closing || !isActive()) {
            return;
        }
        Ukcp ukcp = ukcp();
        long current = System.currentTimeMillis();

        long next;
        long tsUpdate = ukcp.getTsUpdate();
        if (current >= tsUpdate) {
            next = ukcp.flush(current);
            ukcp.setTsUpdate(current + next);
        } else {
            next = tsUpdate - current;
        }

        if (isFlushPending() && ukcp.canSend(false)) {
            unsafe().forceFlush();
        }

        if (closing) {
            return;
        }
        eventLoop().schedule(this, Math.max(1L, next), TimeUnit.MILLISECONDS);
    }

    // ---- KcpListener: what the session reports back -------------------------------------------

    @Override
    public void onConnected(Ukcp ukcp) {
        // Nothing to do: connect() already completed, and the pipeline got channelActive then.
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
        Unsafe unsafe = unsafe();
        unsafe.close(unsafe.voidPromise());
    }

    /** Called by the UDP channel when its socket is gone. */
    void udpChannelClosed() {
        if (closing) {
            return;
        }
        closing = true;
        Unsafe unsafe = unsafe();
        unsafe.close(unsafe.voidPromise());
    }

    // ---- KcpOutput: datagrams the session wants on the wire -----------------------------------

    @Override
    public void out(ByteBuf data, IKcp kcp) {
        Channel.Unsafe unsafe = udpChannel.unsafe();
        unsafe.write(data, unsafe.voidPromise());
        unsafe.flush();
    }

    // ---- IChannelManager: a client has exactly one session ------------------------------------

    @Override
    public Ukcp get(DatagramPacket msg) {
        return ukcp != null && ukcp.isActive() ? ukcp : null;
    }

    @Override
    public void add(SocketAddress socketAddress, Ukcp ukcp, DatagramPacket msg) {
        // A client owns its single session directly; nothing to route.
    }

    @Override
    public void del(Ukcp ukcp) {
        // The channel closes itself in handleClose, before internalClose() gets here.
    }

    @Override
    public Collection<Ukcp> getAll() {
        return ukcp == null ? Collections.<Ukcp>emptyList() : Collections.singletonList(ukcp);
    }

    final class UkcpClientUnsafe extends AbstractUnsafe {

        @Override
        public void connect(SocketAddress remoteAddress, SocketAddress localAddress, ChannelPromise promise) {
            if (!promise.setUncancellable() || !ensureOpen(promise)) {
                return;
            }

            try {
                boolean wasActive = isActive();
                if (doConnect(remoteAddress, localAddress)) {
                    fulfillConnectPromise(promise, wasActive);
                } else {
                    throw new Error();
                }
            } catch (Throwable t) {
                promise.tryFailure(annotateConnectException(t, remoteAddress));
                closeIfClosed();
            }
        }

        private void fulfillConnectPromise(ChannelPromise promise, boolean wasActive) {
            if (promise == null) {
                // Closed via cancellation and the promise has been notified already.
                return;
            }

            // Get the state as trySuccess() may trigger an ChannelFutureListener that will close the Channel.
            // We still need to ensure we call fireChannelActive() in this case.
            boolean active = isActive();

            // trySuccess() will return false if a user cancelled the connection attempt.
            boolean promiseSet = promise.trySuccess();

            // Regardless if the connection attempt was cancelled, channelActive() event should be triggered,
            // because what happened is what happened.
            if (!wasActive && active) {
                pipeline().fireChannelActive();
            }

            // If a user cancelled the connection attempt, close the channel, which is followed by channelInactive().
            if (!promiseSet) {
                close(voidPromise());
            }
        }

        @Override
        protected void flush0() {
            if (isFlushPending()) {
                return;
            }
            super.flush0();
        }

        /** Retries the outbound buffer once the session has room again (the session's timer). */
        void forceFlush() {
            super.flush0();
        }

    }

}