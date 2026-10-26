/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpServerChannel
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import io.github.pasze888.kcptransport.kcp.base.IChannelManager;
import io.github.pasze888.kcptransport.kcp.base.Kcp;
import io.github.pasze888.kcptransport.kcp.base.Ukcp;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * The UDP listening channel. It owns the socket and the session tables, and routes every datagram
 * to its session: by source address on the common path, and by conv once a session has moved to a
 * new source address (NAT rebinding, issue #21).
 *
 * <p>KcpTransport: the sessions are java-kcp's {@code kcp.base.Ukcp}, one per client, and each
 * session runs on its child channel's event loop (ADR-0008, issue #17). The conv is chosen by the
 * client and adopted from its first datagram.
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
public final class UkcpServerChannel extends AbstractNioMessageChannel
        implements ServerChannel, IChannelManager {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(UkcpServerChannel.class);

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(DatagramPacket.class) + ')';

    /** Standard KCP header: conv is a little-endian int at offset 0, sn at 12. */
    private static final int KCP_HEADER_SIZE = 24;
    private static final int KCP_CONV_OFFSET = 0;

    private final DefaultUkcpServerChannelConfig config;

    // KcpTransport: concurrent because #17 turns each session into its own loop and they remove
    // themselves from these tables from their own thread. On one loop it behaves like a plain map.
    //
    // Two indexes over the same children (#21): the address table is the fast path that every
    // datagram with an unchanged source takes, and the conv table is what lets a session survive a
    // NAT rebinding -- same conv, new ip:port. createChildChannel, removeChild and the migration
    // branch below are the only writers; nothing else may touch either map.
    private final ConcurrentHashMap<SocketAddress, UkcpServerChildChannel> childChannelMap =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<Integer, UkcpServerChildChannel> convChannelMap =
            new ConcurrentHashMap<>();

    private static DatagramChannel newSocket(SelectorProvider provider) {
        try {
            /**
             *  Use the {@link SelectorProvider} to open {@link SocketChannel} and so remove condition in
             *  {@link SelectorProvider#provider()} which is called by each DatagramChannel.open() otherwise.
             *
             *  See <a href="https://github.com/netty/netty/issues/2308">#2308</a>.
             */
            return provider.openDatagramChannel();
        } catch (IOException e) {
            throw new ChannelException("Failed to open a socket.", e);
        }
    }

    public UkcpServerChannel() {
        this(newSocket(DEFAULT_SELECTOR_PROVIDER));
    }

    public UkcpServerChannel(SelectorProvider provider) {
        this(newSocket(provider));
    }

    public UkcpServerChannel(DatagramChannel socket) {
        super(null, socket, SelectionKey.OP_READ);
        config = new DefaultUkcpServerChannelConfig(this, socket.socket());
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public UkcpServerChannelConfig config() {
        return config;
    }

    @Override
    protected UkcpServerUnsafe newUnsafe() {
        return new UkcpServerUnsafe();
    }

    @Override
    public boolean isActive() {
        DatagramChannel ch = javaChannel();
        return ch.isOpen() && ch.socket().isBound();
    }

    @Override
    protected DatagramChannel javaChannel() {
        return (DatagramChannel) super.javaChannel();
    }

    @Override
    protected SocketAddress localAddress0() {
        return javaChannel().socket().getLocalSocketAddress();
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return javaChannel().socket().getRemoteSocketAddress();
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        doBind0(localAddress);
    }

    private void doBind0(SocketAddress localAddress) throws Exception {
        if (PlatformDependent.javaVersion() >= 7) {
            SocketUtils.bind(javaChannel(), localAddress);
        } else {
            javaChannel().socket().bind(localAddress);
        }
    }

    @Override
    protected void doClose() throws Exception {
        Exception exception = null;
        try {
            javaChannel().close();
        } catch (Exception t) {
            exception = t;
        }
        // close child channel
        // KcpTransport: every iteration removes the child from this table as it closes, which the
        // concurrent map tolerates without a snapshot.
        for (UkcpServerChildChannel childCh : childChannelMap.values()) {
            try {
                childCh.close();
            } catch (Exception e) {
                log.error("Failed to close a child channel. childChannel={}", childCh);
            }
        }
        if (exception != null) {
            throw exception;
        }
    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        DatagramChannel ch = javaChannel();
        UkcpServerChannelConfig config = config();
        RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();

        ByteBuf data = allocHandle.allocate(config.getAllocator());
        allocHandle.attemptedBytesRead(data.writableBytes());
        boolean free = true;
        try {
            ByteBuffer nioData = data.internalNioBuffer(data.writerIndex(), data.writableBytes());
            int pos = nioData.position();
            InetSocketAddress remoteAddress = (InetSocketAddress) ch.receive(nioData);
            if (remoteAddress == null) {
                return 0;
            }

            allocHandle.lastBytesRead(nioData.position() - pos);
            buf.add(UkcpPacket.newInstance(data.writerIndex(data.writerIndex() + allocHandle.lastBytesRead()),
                    remoteAddress));
            free = false;
            return 1;
        } catch (Throwable cause) {
            PlatformDependent.throwException(cause);
            return -1;
        } finally {
            if (free) {
                data.release();
            }
        }
    }

    @Override
    protected boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception {
        UkcpPacket packet = (UkcpPacket) msg;
        InetSocketAddress remoteAddress = packet.remoteAddress();
        ByteBuf data = packet.content();

        final int dataLen = data.readableBytes();
        if (dataLen == 0) {
            return true;
        }

        final ByteBuffer nioData = data.internalNioBuffer(data.readerIndex(), dataLen);
        final int writtenBytes;
        writtenBytes = javaChannel().send(nioData, remoteAddress);
        return writtenBytes > 0;
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof UkcpPacket) {
            UkcpPacket p = (UkcpPacket) msg;
            ByteBuf content = p.content();
            if (isSingleDirectBuffer(content)) {
                return p;
            }
            content.retain(); // newDirectBuffer method call release method of content
            UkcpPacket np = UkcpPacket.newInstance(newDirectBuffer(content), p.remoteAddress());
            p.release();
            return np;
        }

        throw new UnsupportedOperationException(
                "unsupported message type: " + StringUtil.simpleClassName(msg) + EXPECTED_TYPES);
    }

    /**
     * Checks if the specified buffer is a direct buffer and is composed of a single NIO buffer.
     * (We check this because otherwise we need to make it a non-composite buffer.)
     */
    private static boolean isSingleDirectBuffer(ByteBuf buf) {
        return buf.isDirect() && buf.nioBufferCount() == 1;
    }

    @Override
    protected boolean continueOnWriteError() {
        // Continue on write error as a DatagramChannel can write to multiple remote peers
        //
        // See https://github.com/netty/netty/issues/2665
        return true;
    }

    @Override
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) super.localAddress();
    }

    @Override
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) super.remoteAddress();
    }

    @Override
    protected boolean closeOnReadError(Throwable cause) {
        // We do not want to close on SocketException when using DatagramChannel as we usually can continue receiving.
        // See https://github.com/netty/netty/issues/5893
        if (cause instanceof SocketException) {
            return false;
        }
        return super.closeOnReadError(cause);
    }

    @Override
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doFinishConnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    @Override
    protected void doDisconnect() throws Exception {
        throw new UnsupportedOperationException();
    }

    private UkcpServerChildChannel createChildChannel(InetSocketAddress remoteAddress, int conv) {
        UkcpServerChildChannel ch = new UkcpServerChildChannel(this, remoteAddress, conv);
        childChannelMap.put(remoteAddress, ch);
        // The conv is chosen by the client and is not verified, so a collision is possible. The
        // older session keeps the index: overwriting would make a later migration move the wrong
        // session, and the address index still serves the newcomer.
        UkcpServerChildChannel clash = convChannelMap.putIfAbsent(conv, ch);
        if (clash != null && clash != ch) {
            log.warn("Two sessions share conv {}; the earlier one keeps the conv index", conv);
        }

        // Firing the child through this channel's pipeline is what the bootstrap's acceptor hangs
        // off: it applies the child options (the session copies them when it is built) and
        // registers the channel, which is what attaches the vanilla stack to it.
        ChannelPipeline pipeline = pipeline();
        pipeline.fireChannelRead(ch);
        pipeline.fireChannelReadComplete();

        return ch;
    }

    void removeChild(UkcpServerChildChannel childChannel) {
        // Remove by value, not by key. This runs on the child's own loop while a migration can
        // re-key the same entry on the parent's loop; a key read here could already be stale by
        // the time the removal lands, and the session would stay reachable under its new address
        // forever. Both maps are concurrent, so the value removal is safe.
        childChannelMap.values().remove(childChannel);
        convChannelMap.values().remove(childChannel);
    }

    /** Routes one datagram to its session, creating the session when this is its first one. */
    private void dispatch(UkcpPacket packet) {
        ByteBuf content = packet.content();
        InetSocketAddress remoteAddress = packet.remoteAddress();
        UkcpServerChildChannel childCh = childChannelMap.get(remoteAddress);

        // The session may have moved rather than appeared: same conv, new source
        // address. ADR-0008 left this case open; #21 fixes it, and the migration is deliberately
        // immediate -- the ticket decided against any waiting period, so a racing pair of addresses is
        // accepted as last-writer-wins.
        if (childCh == null && content.readableBytes() >= KCP_HEADER_SIZE) {
            int conv = content.getIntLE(content.readerIndex() + KCP_CONV_OFFSET);
            UkcpServerChildChannel moved = convChannelMap.get(conv);
            if (moved != null && moved.isActive()) {
                InetSocketAddress previous = moved.remoteAddress();
                // Fixed order: publish the new address first, then re-key the address table, then
                // log. Any other order leaves a window where the session is found but its replies
                // still go to the old port.
                moved.relocate(remoteAddress);
                childChannelMap.remove(previous, moved);
                childChannelMap.put(remoteAddress, moved);
                log.info("KCP session migrated: conv={}, {} -> {}", conv, previous, remoteAddress);
                childCh = moved;
            }
        }

        if (childCh == null) {
            // A new session has to start with a first segment (sn == 0); anything else from an
            // unknown peer is somebody else's traffic or a probe. Its conv is adopted from this
            // datagram: java-kcp has no autoSetConv, and its input() rejects a foreign conv.
            if (content.readableBytes() < KCP_HEADER_SIZE
                    || content.getIntLE(content.readerIndex() + Kcp.IKCP_SN_OFFSET) != 0) {
                if (log.isDebugEnabled()) {
                    log.debug("Dropping datagram from an unknown peer: not a first segment. remoteAddress={}",
                            remoteAddress);
                }
                return;
            }
            childCh = createChildChannel(remoteAddress, content.getIntLE(content.readerIndex() + KCP_CONV_OFFSET));
        } else if (!childCh.isActive()) {
            return;
        }

        // The session takes the buffer over (ReadTask releases it after feeding the kernel), so
        // hand it a reference of its own and let UkcpPacket release its own in the caller.
        childCh.kcpRead(content.retain());
    }

    // ---- IChannelManager: the session layer's view of this table ------------------------------

    @Override
    public Ukcp get(DatagramPacket msg) {
        UkcpServerChildChannel childCh = childChannelMap.get(msg.sender());
        return childCh != null && childCh.isActive() ? childCh.ukcp() : null;
    }

    @Override
    public void add(SocketAddress socketAddress, Ukcp ukcp, DatagramPacket msg) {
        // The shell adds children itself in createChildChannel; java-kcp only calls this from its
        // own server handler, which is not part of what we vendored.
    }

    @Override
    public void del(Ukcp ukcp) {
        // Also nothing to do: the child channel removes itself from the table in handleClose,
        // before Ukcp.internalClose() gets here.
    }

    @Override
    public Collection<Ukcp> getAll() {
        List<Ukcp> all = new ArrayList<>(childChannelMap.size());
        for (UkcpServerChildChannel childCh : childChannelMap.values()) {
            Ukcp ukcp = childCh.ukcp();
            if (ukcp != null) {
                all.add(ukcp);
            }
        }
        return all;
    }

    private final class UkcpServerUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    do {
                        int localRead = doReadMessages(readBuf);
                        if (localRead == 0) {
                            break;
                        }
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }

                        allocHandle.incMessagesRead(localRead);
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    exception = t;
                }

                int readBufSize = readBuf.size();
                for (int i = 0; i < readBufSize; i++) {
                    UkcpPacket packet = (UkcpPacket) readBuf.get(i);
                    try {
                        dispatch(packet);
                    } catch (Throwable t) {
                        log.error("Failed to feed a datagram to its session", t);
                        pipeline.fireExceptionCaught(t);
                    } finally {
                        packet.release();
                    }
                }
                readBuf.clear();
                allocHandle.readComplete();

                if (exception != null) {
                    closed = closeOnReadError(exception);

                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

}