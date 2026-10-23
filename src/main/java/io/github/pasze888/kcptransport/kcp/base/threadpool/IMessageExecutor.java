/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), IMessageExecutor.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base.threadpool;

/**
 * 消息处理器
 */
public interface IMessageExecutor {
    /**
     * 停止消息处理器
     */
    void stop();

    /**
     * 判断队列是否已经达到上限了
     *
     * @return {@link true}：队列已达上限
     */
    boolean isFull();

    /**
     * 执行任务
     * 注意: 如果线程等于当前线程 则直接执行  如果非当前线程放进队列
     *
     * @param iTask 任务对象
     */
    void execute(ITask iTask);
}