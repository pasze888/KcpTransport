package io.github.pasze888.kcptransport.kcp;

import io.github.pasze888.kcptransport.kcp.base.ChannelConfig;
import io.github.pasze888.kcptransport.kcp.base.KcpConfig;
import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutor;
import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutorPool;

/**
 * The vendored session config a {@code kcp.base.Ukcp} reads once at construction: conv, every KCP
 * knob, fast-flush and buffer sizes.
 *
 * <p>The vendored {@link ChannelConfig} constructor builds an {@code IMessageExecutorPool} of its
 * own when none is passed -- a whole {@code DefaultEventLoopGroup}, one per KCP channel, never
 * shut down. Our sessions get their executor handed to {@code Ukcp} directly, so the pool would
 * only leak. Passing a pool that is never asked for keeps the vendored class untouched.
 */
public final class UkcpSessionConfig extends ChannelConfig {

    private static final IMessageExecutorPool UNUSED_POOL = new IMessageExecutorPool() {
        @Override
        public IMessageExecutor getIMessageExecutor() {
            throw new UnsupportedOperationException(
                    "the session executor is handed to Ukcp directly, no pool involved");
        }

        @Override
        public void stop() {
        }
    };

    public UkcpSessionConfig(KcpConfig kcpConfig) {
        super(kcpConfig, UNUSED_POOL);
    }
}