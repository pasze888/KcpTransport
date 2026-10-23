/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), IMessageExecutorPool.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base.threadpool;

/**
 * Created by JinMiao
 * 2020/11/24.
 */
public interface IMessageExecutorPool {
    /**
     * 从线程池中按算法获得一个线程对象
     *
     * @return {@link IMessageExecutor}
     */
    IMessageExecutor getIMessageExecutor();

    void stop();

}
