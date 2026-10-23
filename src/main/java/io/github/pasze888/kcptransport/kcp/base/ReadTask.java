/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), ReadTask.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

import io.github.pasze888.kcptransport.kcp.base.internal.CodecOutputList;
import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.pasze888.kcptransport.kcp.base.threadpool.ITask;

import java.util.Queue;

/**
 * Created by JinMiao
 * 2018/9/11.
 */
public class ReadTask implements ITask {

    private static final Logger logger = LoggerFactory.getLogger(ReadTask.class);

    protected final Ukcp ukcp;

    public ReadTask(Ukcp ukcp) {
        this.ukcp = ukcp;
    }

    @Override
    public void execute() {
        CodecOutputList<ByteBuf> bufList = null;
        Ukcp ukcp = this.ukcp;
        try {
            //查看连接状态
            if (!ukcp.isActive()) {
                return;
            }
            long current = System.currentTimeMillis();
            Queue<ByteBuf> receiveList = ukcp.getReadBuffer();
            int readCount = 0;
            for (; ; ) {
                ByteBuf byteBuf = receiveList.poll();
                if (byteBuf == null) {
                    break;
                }
                readCount++;
                ukcp.input(byteBuf, current);
                byteBuf.release();
            }
            if (readCount == 0) {
                return;
            }
            if (ukcp.isControlReadBufferSize()) {
                ukcp.getReadBufferIncr().addAndGet(readCount);
            }
            long readBytes = 0;
            if (ukcp.isStream()) {
                int size = 0;
                while (ukcp.canRecv()) {
                    if (bufList == null) {
                        bufList = CodecOutputList.newInstance();
                    }
                    ukcp.receive(bufList);
                    size = bufList.size();
                }
                for (int i = 0; i < size; i++) {
                    ByteBuf byteBuf = bufList.getUnsafe(i);
                    readBytes += byteBuf.readableBytes();
                    readByteBuf(byteBuf, current, ukcp);
                }
            } else {
                while (ukcp.canRecv()) {
                    ByteBuf recvBuf = ukcp.mergeReceive();
                    readBytes += recvBuf.readableBytes();
                    readByteBuf(recvBuf, current, ukcp);
                }
            }
            //判断写事件
            if (!ukcp.getWriteBuffer().isEmpty() && ukcp.canSend(false)) {
                ukcp.notifyWriteEvent();
            }
        } catch (Throwable e) {
            ukcp.internalClose();
            logger.error(e.getMessage(), e);
        } finally {
            release();
            if (bufList != null) {
                bufList.recycle();
            }
        }
    }

    protected void readByteBuf(ByteBuf buf, long current, Ukcp ukcp) {
        ukcp.setLastReceiveTime(current);
        try {
            ukcp.getKcpListener().handleReceive(buf, ukcp);
        } catch (Throwable throwable) {
            ukcp.getKcpListener().handleException(throwable, ukcp);
        } finally {
            buf.release();
        }
    }

    public void release() {
        ukcp.getReadProcessing().set(false);
    }

}
