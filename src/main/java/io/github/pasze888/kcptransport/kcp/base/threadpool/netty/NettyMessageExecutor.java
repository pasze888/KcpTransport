/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), NettyMessageExecutor.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base.threadpool.netty;

import io.netty.channel.EventLoop;
import io.github.pasze888.kcptransport.kcp.base.threadpool.IMessageExecutor;
import io.github.pasze888.kcptransport.kcp.base.threadpool.ITask;

/**
 * Created by JinMiao
 * 2020/11/24.
 */
public class NettyMessageExecutor implements IMessageExecutor {

    private final EventLoop eventLoop;

    public NettyMessageExecutor(EventLoop eventLoop) {
        this.eventLoop = eventLoop;
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isFull() {
        return false;
    }

    @Override
    public void execute(ITask iTask) {
        //if(eventLoop.inEventLoop()){
        //    iTask.execute();
        //}else{
        this.eventLoop.execute(iTask::execute);
        //}
    }
}
