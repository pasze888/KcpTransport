package io.github.pasze888.kcptransport.kcp;

import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutor;
import io.github.pasze888.kcptransport.kcp.base.threadpool.ITask;

/**
 * Runs a session's read/write tasks on the thread that asked for them.
 *
 * <p>ADR-0007 brings java-kcp's session layer in, and that layer routes every task through an
 * {@link IMessageExecutor}. ADR-0008 wants one event loop per session, which is issue #17; until
 * then each channel family still owns exactly one event loop (the parent channel's for the server,
 * the client channel's for the client), so a thread hop would only add a queue and latency. This
 * executor keeps the same single-threaded invariant the shipped code has today.
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