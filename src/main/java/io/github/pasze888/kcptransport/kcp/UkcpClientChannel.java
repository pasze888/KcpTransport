/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpClientChannel
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import static io.github.pasze888.kcptransport.kcp.Consts.sheduleUpdateLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.AbstractChannel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.IoEventLoop;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public final class UkcpClientChannel extends AbstractChannel implements UkcpChannel, Runnable {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(UkcpClientChannel.class);

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';

    private final DefaultUkcpClientChannelConfig config;

    private final UkcpClientUdpChannel udpChannel;

    private final Ukcp ukcp;

    private final KcpOutput output = new UkcpClientOutput();

    private int tsUpdate;

    private boolean scheduleUpdate;

    private boolean flushPending;

    boolean closeAnother = false;

    public UkcpClientChannel() {
        super(null);
        this.udpChannel = new UkcpClientUdpChannel(this);
        this.ukcp = createUkcp();
        this.config = new DefaultUkcpClientChannelConfig(this, ukcp, udpChannel.javaChannel().socket());
    }

    private Ukcp createUkcp() {
        // KcpTransport: upstream used conv 0 and left assigning it to the caller, so every
        // client shared conv 0. Give each channel its own non-zero conv; conv(int) still
        // overrides it (e.g. to resume a session with the previous conv on reconnect).
        Ukcp ukcp = new Ukcp(nextConv(), output);
        ukcp.channel(this);

        return ukcp;
    }

    private static int nextConv() {
        int conv;
        do {
            conv = ThreadLocalRandom.current().nextInt();
        } while (conv == 0);
        return conv;
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
        return udpChannel.doConnect(remoteAddress, localAddress);
    }

    @Override
    protected void doDisconnect() throws Exception {
        udpChannel.doDisconnect();
    }

    @Override
    protected void doClose() throws Exception {
        ukcp.setClosed(true);
        if (!closeAnother) {
            closeAnother = true;
            udpChannel.unsafe().close(udpChannel.unsafe().voidPromise());
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        udpChannel.doBeginRead();
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        boolean sent = false;
        for (; ; ) {
            Object msg = in.current();
            if (msg == null) {
                flushPending = false;
                break;
            }
            try {
                boolean done = false;
                if (kcpSend((ByteBuf) msg)) {
                    done = true;
                    sent = true;
                }

                if (done) {
                    in.remove();
                } else {
                    flushPending = true;
                    break;
                }
            } catch (IOException e) {
                throw e; // throw exception and close channel
            }
        }

        if (sent) {
            // update kcp
            if (ukcp.isFastFlush()) {
                updateKcp();
            } else {
                kcpTsUpdate(Utils.milliSeconds());
            }
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

    public int conv() {
        return ukcp.getConv();
    }

    public UkcpClientChannel conv(int conv) {
        ukcp.setConv(conv);
        return this;
    }

    public boolean kcpIsActive() {
        return ukcp.isActive();
    }

    void kcpReceive(ByteBuf buf) throws IOException {
        ukcp.receive(buf);
    }

    void kcpReceive(List<ByteBuf> bufList) {
        ukcp.receive(bufList);
    }

    void kcpInput(ByteBuf buf) throws IOException {
        ukcp.input(buf);
    }

    boolean kcpSend(ByteBuf buf) throws IOException {
        if (ukcp.canSend(true)) {
            ukcp.send(buf);
            return true;
        } else {
            return false;
        }
    }

    boolean kcpCanRecv() {
        return ukcp.canRecv();
    }

    boolean kcpCanSend() {
        return ukcp.canSend(!flushPending);
    }

    int kcpPeekSize() {
        return ukcp.peekSize();
    }

    int kcpUpdate(int current) {
        return ukcp.update(current);
    }

    int kcpCheck(int current) {
        return ukcp.check(current);
    }

    int kcpTsUpdate() {
        return ukcp.getTsUpdate();
    }

    void kcpTsUpdate(int tsUpdate) {
        ukcp.setTsUpdate(tsUpdate);
    }

    int kcpState() {
        return ukcp.getState();
    }

    void scheduleUpdate(int tsUpdate, int current) {
        if (sheduleUpdateLog.isDebugEnabled()) {
            sheduleUpdateLog.debug("schedule delay: " + (tsUpdate - current));
        }
        this.tsUpdate = tsUpdate;
        this.scheduleUpdate = true;
        eventLoop().schedule(this, tsUpdate - current, TimeUnit.MILLISECONDS);
    }

    @Override
    public void run() {
        if (!isActive()) {
            return;
        }
        int current = Utils.milliSeconds();

        int nextTsUpdate = 0;
        boolean nextSchedule = false;
        int tsUp = kcpTsUpdate();
        Throwable exception = null;
        if (Utils.itimediff(current, tsUp) >= 0) {
            try {
                nextTsUpdate = kcpUpdate(current);
                nextSchedule = true;
            } catch (Throwable t) {
                exception = t;
            }

            if (kcpState() == -1 && exception == null) {
                // KcpTransport: dead link is a normal end of session for UDP, close quietly.
                if (log.isDebugEnabled()) {
                    log.debug("getState=-1 after update(). channel={}", this);
                }
                this.tsUpdate = 0;
                this.scheduleUpdate = false;
                Utils.closeQuietly(this);
                return;
            }
        } else {
            nextTsUpdate = tsUp;
            nextSchedule = true;
        }

        boolean close = false;
        if (exception != null) {
            close = true;
            nextTsUpdate = 0;
            nextSchedule = false;
        } else {
            if (isFlushPending() && kcpCanSend()) {
                unsafe().forceFlush();
            }
        }

        this.tsUpdate = nextTsUpdate;
        this.scheduleUpdate = nextSchedule;
        if (nextSchedule) {
            scheduleUpdate(tsUpdate, current);
        }

        if (close) {
            Utils.fireExceptionAndClose(this, exception, true);
        }
    }

    private void updateKcp() {
        int current = Utils.milliSeconds();
        Throwable exception = null;
        try {
            kcpUpdate(current);
        } catch (Throwable t) {
            exception = t;
        }

        if (kcpState() == -1 && exception == null) {
            // KcpTransport: dead link is a normal end of session for UDP, close quietly.
            if (log.isDebugEnabled()) {
                log.debug("getState=-1 after update(). channel={}", this);
            }
            Utils.closeQuietly(this);
            return;
        }

        if (exception != null) {
            Utils.fireExceptionAndClose(this, exception, true);
        }
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

        void forceFlush() {
            super.flush0();
        }

    }

    private static class UkcpClientOutput implements KcpOutput {

        @Override
        public void out(ByteBuf data, Kcp kcp) {
            UkcpClientChannel ukcpChannel = (UkcpClientChannel) kcp.getUser();
            UkcpClientUdpChannel udpChannel = ukcpChannel.udpChannel;
            udpChannel.unsafe().write(data, udpChannel.voidPromise());
            udpChannel.unsafe().flush();
        }

    }

}
