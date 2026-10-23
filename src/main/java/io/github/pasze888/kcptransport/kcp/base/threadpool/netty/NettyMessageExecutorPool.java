/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), NettyMessageExecutorPool.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base.threadpool.netty;

import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.EventLoopGroup;
import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutor;
import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutorPool;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 基于netty eventloop的线程池
 * Created by JinMiao
 * 2020/11/24.
 */
public class NettyMessageExecutorPool implements IMessageExecutorPool {

    private final EventLoopGroup eventExecutors;

    protected static final AtomicInteger index = new AtomicInteger();

    public NettyMessageExecutorPool(int workSize) {
        eventExecutors = new DefaultEventLoopGroup(workSize, r -> {
            return new Thread(r, "nettyMessageExecutorPool-" + index.incrementAndGet());
        });
    }

    @Override
    public IMessageExecutor getIMessageExecutor() {
        return new NettyMessageExecutor(eventExecutors.next());
    }

    @Override
    public void stop() {
        if (!eventExecutors.isShuttingDown()) {
            eventExecutors.shutdownGracefully();
        }
    }
}
