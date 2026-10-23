/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), UkcpClientUdpChannel
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelMetadata;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.nio.AbstractNioMessageChannel;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.SocketUtils;
import io.netty.util.internal.StringUtil;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * The UDP socket of a client channel. It owns the socket and nothing else: datagrams go straight
 * into the session, which is where the state machine and the reassembly now live.
 *
 * <p>KcpTransport: the read loop used to drive kcp input and merge segments here. java-kcp's
 * {@code kcp.base.Ukcp} does that in its own read task, so all this channel does is hand each
 * datagram over.
 *
 * @author <a href="mailto:szhnet@gmail.com">szh</a>
 */
final class UkcpClientUdpChannel extends AbstractNioMessageChannel {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(UkcpClientUdpChannel.class);

    private static final ChannelMetadata METADATA = new ChannelMetadata(false);

    private static final SelectorProvider DEFAULT_SELECTOR_PROVIDER = SelectorProvider.provider();
    private static final String EXPECTED_TYPES =
            " (expected: " + StringUtil.simpleClassName(ByteBuf.class) + ')';

    private final UkcpClientChannel ukcpChannel;

    boolean inputShutdown;

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

    public UkcpClientUdpChannel(UkcpClientChannel ukcpChannel) {
        this(ukcpChannel, newSocket(DEFAULT_SELECTOR_PROVIDER));
    }

    public UkcpClientUdpChannel(UkcpClientChannel ukcpChannel, SelectorProvider provider) {
        this(ukcpChannel, newSocket(provider));
    }

    public UkcpClientUdpChannel(UkcpClientChannel ukcpChannel, DatagramChannel socket) {
        super(null, socket, SelectionKey.OP_READ);
        this.ukcpChannel = ukcpChannel;
    }

    @Override
    public ChannelMetadata metadata() {
        return METADATA;
    }

    @Override
    public ChannelConfig config() {
        return ukcpChannel.config();
    }

    @Override
    protected UkcpClientUdpUnsafe newUnsafe() {
        return new UkcpClientUdpUnsafe();
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
    protected boolean doConnect(SocketAddress remoteAddress, SocketAddress localAddress) throws Exception {
        if (localAddress != null) {
            doBind0(localAddress);
        }

        boolean success = false;
        try {
            javaChannel().connect(remoteAddress);
            success = true;
            return true;
        } finally {
            if (!success) {
                doClose();
            }
        }
    }

    @Override
    protected void doFinishConnect() throws Exception {
        throw new Error();
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        javaChannel().close();
        ukcpChannel.udpChannelClosed();
    }

    /** Closes just the socket; the owning channel is already on its way down. */
    void closeFromOwner() {
        if (isOpen()) {
            Unsafe unsafe = unsafe();
            unsafe.close(unsafe.voidPromise());
        }
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();

    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        DatagramChannel ch = javaChannel();
        ChannelConfig config = config();
        RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();

        ByteBuf data = allocHandle.allocate(config.getAllocator());
        allocHandle.attemptedBytesRead(data.writableBytes());
        boolean free = true;
        try {
            ByteBuffer nioData = data.internalNioBuffer(data.writerIndex(), data.writableBytes());
            int pos = nioData.position();
            int read = ch.read(nioData);
            if (read <= 0) {
                return read;
            }

            allocHandle.lastBytesRead(nioData.position() - pos);
            buf.add(data.writerIndex(data.writerIndex() + allocHandle.lastBytesRead()));
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
        ByteBuf data = (ByteBuf) msg;

        final int dataLen = data.readableBytes();
        if (dataLen == 0) {
            return true;
        }

        final ByteBuffer nioData = data.internalNioBuffer(data.readerIndex(), dataLen);
        final int writtenBytes;
        writtenBytes = javaChannel().write(nioData);
        return writtenBytes > 0;
    }

    @Override
    protected Object filterOutboundMessage(Object msg) {
        if (msg instanceof ByteBuf) {
            ByteBuf buf = (ByteBuf) msg;
            if (isSingleDirectBuffer(buf)) {
                return buf;
            }
            return newDirectBuffer(buf);
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

    private final class UkcpClientUdpUnsafe extends AbstractNioUnsafe {

        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            assert eventLoop().inEventLoop();
            final ChannelConfig config = config();
            final ChannelPipeline ukcpPipeline = ukcpChannel.pipeline();
            final RecvByteBufAllocator.Handle allocHandle = recvBufAllocHandle();
            allocHandle.reset(config);

            boolean closed = false;
            boolean readAny = false;
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
                readAny = readBufSize > 0;
                for (int i = 0; i < readBufSize; i++) {
                    // The session takes the buffer over and releases it once it has fed the
                    // kernel, so this loop must not release anything itself.
                    ukcpChannel.kcpRead((ByteBuf) readBuf.get(i));
                }
                readBuf.clear();
                allocHandle.readComplete();

                if (readAny) {
                    ukcpPipeline.fireChannelReadComplete();
                }

                if (exception != null) {
                    closed = closeOnReadError(exception);

                    ukcpPipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
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