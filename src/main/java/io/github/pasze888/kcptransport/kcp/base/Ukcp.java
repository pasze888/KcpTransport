/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), Ukcp.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;
import io.netty.util.internal.PlatformDependent;
import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutor;

import java.io.IOException;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class Ukcp {

    private static final InternalLogger log = InternalLoggerFactory.getInstance(Ukcp.class);

    private final IKcp kcp;

    private boolean fastFlush = true;

    private long tsUpdate = -1;

    private boolean active;

    private final Queue<ByteBuf> writeBuffer;

    private final Queue<ByteBuf> readBuffer;

    private final IMessageExecutor iMessageExecutor;

    private final KcpListener kcpListener;

    private final long timeoutMillis;

    private final IChannelManager channelManager;

    private final AtomicBoolean writeProcessing = new AtomicBoolean(false);

    private final AtomicBoolean readProcessing = new AtomicBoolean(false);

    private final AtomicInteger readBufferIncr = new AtomicInteger(-1);

    private final AtomicInteger writeBufferIncr = new AtomicInteger(-1);

    private final WriteTask writeTask = new WriteTask(this);

    private final ReadTask readTask = new ReadTask(this);

    private boolean controlReadBufferSize = false;

    private boolean controlWriteBufferSize = false;

    /**
     * 上次收到消息时间
     **/
    private long lastReceiveTime = System.currentTimeMillis();

    /**
     * Creates a new instance.
     *
     * @param output output for kcp
     */
    public Ukcp(KcpOutput output, KcpListener kcpListener, IMessageExecutor iMessageExecutor, ChannelConfig channelConfig,
                IChannelManager channelManager) {
        KcpConfig kcpConfig = channelConfig.getKcpConfig();
        this.timeoutMillis = channelConfig.getTimeoutMillis();
        this.kcp = new Kcp(kcpConfig.getConv(), output);
        this.active = true;
        this.kcpListener = kcpListener;
        this.iMessageExecutor = iMessageExecutor;
        this.channelManager = channelManager;
        // KcpTransport: jctools' MpscLinkedQueue replaced by Netty's own MPSC queue factory, so
        // the mod adds no runtime dependency. Same contract (lock-free, MPSP); Netty's queue
        // is JCTools-backed internally.
        this.writeBuffer = PlatformDependent.newMpscQueue();
        this.readBuffer = PlatformDependent.newMpscQueue();
        this.fastFlush = channelConfig.isFastFlush();

        if (channelConfig.getReadBufferSize() != -1) {
            this.controlReadBufferSize = true;
            this.readBufferIncr.set(channelConfig.getReadBufferSize() / kcpConfig.getMtu());
        }

        if (channelConfig.getWriteBufferSize() != -1) {
            this.controlWriteBufferSize = true;
            this.writeBufferIncr.set(channelConfig.getWriteBufferSize() / kcpConfig.getMtu());
        }


        initKcpConfig(kcpConfig);
    }

    private void initKcpConfig(KcpConfig kcpConfig) {
        kcp.nodelay(kcpConfig.isNodelay(), kcpConfig.getInterval(), kcpConfig.getFastresend(), kcpConfig.isNocwnd());
        kcp.setSndWnd(kcpConfig.getSndwnd());
        kcp.setRcvWnd(kcpConfig.getRcvwnd());
        kcp.setMtu(kcpConfig.getMtu());
        kcp.setStream(kcpConfig.isStream());
        kcp.setAckNoDelay(kcpConfig.isAckNoDelay());
        kcp.setAckMaskSize(kcpConfig.getAckMaskSize());
    }

    /**
     * Receives ByteBuf list.
     *
     * @param bufList received ByteBuf will be add to the list
     */
    protected void receive(List<ByteBuf> bufList) {
        kcp.recv(bufList);
    }

    protected ByteBuf mergeReceive() {
        return kcp.mergeRecv();
    }

    protected void input(ByteBuf data, long current) throws IOException {
        // KcpTransport: the FEC/CRC32 demux upstream performs here is not vendored; every
        // datagram goes straight into the state machine, which is what standard KCP expects.
        input(data, true, current);
    }

    private void input(ByteBuf data, boolean regular, long current) throws IOException {
        int ret = kcp.input(data, regular, current);
        // KcpTransport: negative codes mean "this datagram is not KCP for this session" (truncated
        // header or body, bad command, another session's conv). Upstream throws, but ReadTask turns
        // any throw into internalClose, so a port scan or a health check aimed at the UDP port would
        // tear down live sessions. The C original drops the datagram and keeps the session; so do we
        // (the same fork kcp-netty needed for its upstream issue #15).
        if (ret < 0 && log.isDebugEnabled()) {
            log.debug("Dropping malformed KCP datagram, ret={}, bytes={}", ret, data.readableBytes());
        }
    }

    /**
     * Sends a ByteBuf.
     *
     * @param buf
     * @throws IOException
     */
    void send(ByteBuf buf) throws IOException {
        int ret = kcp.send(buf);
        if (ret == -2) {
            throw new IOException("Too many fragments");
        }
    }

    /**
     * @return {@code true} if there are bytes can be received.
     */
    protected boolean canRecv() {
        return kcp.canRecv();
    }

    protected IKcp getKcp() {
        return kcp;
    }

    protected long getLastReceiveTime() {
        return lastReceiveTime;
    }

    protected void setLastReceiveTime(long lastReceiveTime) {
        this.lastReceiveTime = lastReceiveTime;
    }

    /**
     * Returns {@code true} if the kcp can send more bytes.
     *
     * @param curCanSend last state of canSend
     * @return {@code true} if the kcp can send more bytes
     */
    // KcpTransport: widened from protected for the shells' driver loop, which retries a write the
    // session had to refuse once the send window opens again.
    public boolean canSend(boolean curCanSend) {
        int max = kcp.getSndWnd() * 2;
        int waitSnd = kcp.waitSnd();
        if (curCanSend) {
            return waitSnd < max;
        } else {
            int threshold = Math.max(1, max / 2);
            return waitSnd < threshold;
        }
    }

    /**
     * Updates the kcp.
     *
     * @param current current time in milliseconds
     * @return the next time to update
     */
    protected long update(long current) {
        kcp.update(current);
        long nextTsUp = check(current);
        setTsUpdate(nextTsUp);

        return nextTsUp;
    }

    // KcpTransport: widened from protected for the Netty shells in the parent package; the return
    // value is the delay in milliseconds until the next flush.
    public long flush(long current) {
        return kcp.flush(false, current);
    }

    /**
     * Determines when should you invoke update.
     *
     * @param current current time in milliseconds
     * @see Kcp#check(long)
     */
    protected long check(long current) {
        return kcp.check(current);
    }

    /**
     * Returns {@code true} if the kcp need to flush.
     *
     * @return {@code true} if the kcp need to flush
     */
    protected boolean checkFlush() {
        return kcp.checkFlush();
    }

    /**
     * Returns conv of kcp.
     *
     * @return conv of kcp
     */
    public int getConv() {
        return kcp.getConv();
    }

    /**
     * Set the conv of kcp.
     *
     * @param conv the conv of kcp
     */
    public void setConv(int conv) {
        kcp.setConv(conv);
    }


    /**
     * Returns update interval.
     *
     * @return update interval
     */
    protected int getInterval() {
        return kcp.getInterval();
    }


    protected boolean isStream() {
        return kcp.isStream();
    }


    /**
     * Sets the {@link ByteBufAllocator} which is used for the kcp to allocate buffers.
     *
     * @param allocator the allocator is used for the kcp to allocate buffers
     * @return this object
     */
    public Ukcp setByteBufAllocator(ByteBufAllocator allocator) {
        kcp.setByteBufAllocator(allocator);
        return this;
    }

    protected boolean isFastFlush() {
        return fastFlush;
    }

    // KcpTransport: widened from protected for the shells, which drive the session from their own
    // channel instead of subclassing the vendored class or reaching into it with reflection.
    public void read(ByteBuf byteBuf) {
        if (controlReadBufferSize) {
            int readBufferSize = readBufferIncr.getAndUpdate(operand -> {
                if (operand == 0) {
                    return operand;
                }
                return --operand;
            });
            if (readBufferSize == 0) {
                //TODO 这里做的不对 应该丢弃队列最早的那个消息包  这样子丢弃有一定的概率会卡死 以后优化
                byteBuf.release();
                log.error("conv {} address {} readBuffer is full", kcp.getConv(), ((User) kcp.getUser()).getRemoteAddress());
                return;
            }
        }
        this.readBuffer.offer(byteBuf);
        notifyReadEvent();
    }

    /**
     * 发送有序可靠消息
     * 线程安全的
     *
     * @param byteBuf 发送后需要手动调用 {@link ByteBuf#release()}
     * @return true发送成功  false缓冲区满了
     */
    public boolean write(ByteBuf byteBuf) {
        if (controlWriteBufferSize) {
            int bufferSize = writeBufferIncr.getAndUpdate(operand -> {
                if (operand == 0) {
                    return operand;
                }
                return --operand;
            });
            if (bufferSize == 0) {
                //log.error("conv {} address {} writeBuffer is full",kcp.getConv(),((User)kcp.getUser()).getRemoteAddress());
                return false;
            }
        }
        byteBuf = byteBuf.retainedDuplicate();
        writeBuffer.offer(byteBuf);
        notifyWriteEvent();
        return true;
    }

    protected AtomicInteger getReadBufferIncr() {
        return readBufferIncr;
    }

    /**
     * 主动关闭连接调用
     */
    public void close() {
        this.iMessageExecutor.execute(this::internalClose);
    }

    private void notifyReadEvent() {
        if (readProcessing.compareAndSet(false, true)) {
            this.iMessageExecutor.execute(this.readTask);
        }
    }

    protected void notifyWriteEvent() {
        if (writeProcessing.compareAndSet(false, true)) {
            this.iMessageExecutor.execute(this.writeTask);
        }
    }

    // KcpTransport: widened from protected for the shells' single driver loop.
    public long getTsUpdate() {
        return tsUpdate;
    }

    protected Queue<ByteBuf> getReadBuffer() {
        return readBuffer;
    }

    // KcpTransport: widened from protected for the shells' single driver loop.
    public Ukcp setTsUpdate(long tsUpdate) {
        this.tsUpdate = tsUpdate;
        return this;
    }

    protected Queue<ByteBuf> getWriteBuffer() {
        return writeBuffer;
    }

    protected KcpListener getKcpListener() {
        return kcpListener;
    }

    public boolean isActive() {
        return active;
    }

    void internalClose() {
        if (!active) {
            return;
        }
        this.active = false;
        notifyReadEvent();
        kcpListener.handleClose(this);
        //关闭之前尽量把消息都发出去
        notifyWriteEvent();
        kcp.flush(false, System.currentTimeMillis());
        //连接删除
        channelManager.del(this);
        release();
    }

    void release() {
        kcp.setState(-1);
        kcp.release();
        for (; ; ) {
            ByteBuf byteBuf = writeBuffer.poll();
            if (byteBuf == null) {
                break;
            }
            byteBuf.release();
        }
        for (; ; ) {
            ByteBuf byteBuf = readBuffer.poll();
            if (byteBuf == null) {
                break;
            }
            byteBuf.release();
        }
    }

    protected AtomicBoolean getWriteProcessing() {
        return writeProcessing;
    }


    protected AtomicBoolean getReadProcessing() {
        return readProcessing;
    }

    protected IMessageExecutor getiMessageExecutor() {
        return iMessageExecutor;
    }

    protected long getTimeoutMillis() {
        return timeoutMillis;
    }

    protected AtomicInteger getWriteBufferIncr() {
        return writeBufferIncr;
    }

    protected boolean isControlReadBufferSize() {
        return controlReadBufferSize;
    }


    protected boolean isControlWriteBufferSize() {
        return controlWriteBufferSize;
    }

    /**
     * 当前 kcp 是否可写数据
     *
     * @return boolean 如果可以写数据，后续调用 {@link #write(ByteBuf)}
     */
    public boolean isWriteable() {
        if (controlReadBufferSize) {
            return writeBufferIncr.get() != 0;
        }
        return true;
    }

    /**
     * 当前 kcp 是否可读数据
     *
     * @return boolean
     */
    public boolean isReadable() {
        if (controlReadBufferSize) {
            return readBufferIncr.get() != 0;
        }
        return true;
    }

    public User user() {
        return (User) kcp.getUser();
    }

    protected Ukcp user(User user) {
        kcp.setUser(user);
        return this;
    }

    @Override
    public String toString() {
        return "Ukcp(" +
                "getConv=" + kcp.getConv() +
                ", state=" + kcp.getState() +
                ", active=" + active +
                ')';
    }
}
