/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), KcpListener.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

import io.netty.buffer.ByteBuf;

/**
 * Created by JinMiao
 * 2018/9/11.
 */
public interface KcpListener {


    /**
     * 连接之后
     * @param ukcp
     */
    void onConnected(Ukcp ukcp);

    /**
     * kcp message
     *
     * @param byteBuf the data
     * @param ukcp
     */
    void handleReceive(ByteBuf byteBuf, Ukcp ukcp);

    /**
     *
     * kcp异常，之后此kcp就会被关闭
     *
     * @param ex 异常
     * @param ukcp 发生异常的kcp，null表示非kcp错误
     */
    void handleException(Throwable ex, Ukcp ukcp);

    /**
     * 关闭
     *
     * @param ukcp
     */
    void handleClose(Ukcp ukcp);
}
