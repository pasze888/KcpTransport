package io.github.pasze888.kcptransport.kcp;

import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutor;
import io.github.pasze888.kcptransport.kcp.base.threadpool.ITask;

/**
 * Runs a session's read/write tasks on the thread that asked for them.
 *
 * <p>ADR-0007 brings java-kcp's session layer in, and that layer routes every task through an
 * {@link IMessageExecutor}. The server now supplies a Netty executor per child loop; the client
 * still uses this executor because its session and UDP channel already share one event loop, so a
 * thread hop would only add a queue and latency.
 */
public final class InlineMessageExecutor implements IMessageExecutor {

    public static final InlineMessageExecutor INSTANCE = new InlineMessageExecutor();

    private InlineMessageExecutor() {
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isFull() {
        return false;
    }

    @Override
    public void execute(ITask task) {
        task.execute();
    }
}