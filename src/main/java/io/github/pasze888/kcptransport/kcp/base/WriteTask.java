/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), WriteTask.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

import io.netty.buffer.ByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.pasze888.kcptransport.kcp.base.threadpool.ITask;

import java.io.IOException;
import java.util.Queue;

/**
 * Created by JinMiao
 * 2018/9/11.
 */
public class WriteTask implements ITask {

    private static final Logger logger = LoggerFactory.getLogger(WriteTask.class);

    protected final Ukcp ukcp;

    public WriteTask(Ukcp ukcp) {
        this.ukcp = ukcp;
    }

    @Override
    public void execute() {
        Ukcp ukcp = this.ukcp;
        try {
            //查看连接状态
            if (!ukcp.isActive()) {
                return;
            }
            //从发送缓冲区到kcp缓冲区
            Queue<ByteBuf> queue = ukcp.getWriteBuffer();
            int writeCount = 0;
            long writeBytes = 0;
            while (ukcp.canSend(false)) {
                ByteBuf byteBuf = queue.poll();
                if (byteBuf == null) {
                    break;
                }
                writeCount++;
                try {
                    writeBytes += byteBuf.readableBytes();
                    ukcp.send(byteBuf);
                    byteBuf.release();
                } catch (IOException e) {
                    ukcp.getKcpListener().handleException(e, ukcp);
                    return;
                }
            }
            if (ukcp.isControlWriteBufferSize()) {
                ukcp.getWriteBufferIncr().addAndGet(writeCount);
            }
            //如果有发送 则检测时间
            if (!ukcp.canSend(false) || (ukcp.checkFlush() && ukcp.isFastFlush())) {
                long now = System.currentTimeMillis();
                long next = ukcp.flush(now);
                ukcp.setTsUpdate(now + next);
            }
        } catch (Throwable e) {
            logger.error(e.getMessage(), e);
        } finally {
            release();
        }
    }

    public void release() {
        ukcp.getWriteProcessing().set(false);
    }
}
