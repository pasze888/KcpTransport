/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), IChannelManager.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

import io.netty.channel.socket.DatagramPacket;

import java.net.SocketAddress;
import java.util.Collection;


/**
 * Created by JinMiao
 * 2019/10/16.
 */
public interface IChannelManager {
    /**
     * 根据 UDP 消息读取到指定的 kcp 连接对象
     *
     * @param msg UDP消息
     * @return {@link Ukcp} 对象，如果不存在则返回 {@link null}
     */
    Ukcp get(DatagramPacket msg);

    /**
     * Deprecated. 请使用 {@link #add(SocketAddress, Ukcp, DatagramPacket)} 方法
     */
    @Deprecated
    default void New(SocketAddress socketAddress, Ukcp ukcp, DatagramPacket msg) {
        add(socketAddress, ukcp, msg);
    }

    /**
     * 新增一个 kcp 连接对象
     *
     * @param socketAddress 与 {@link Ukcp} 绑定的地址
     * @param ukcp          {@link Ukcp} 对象
     * @param msg           UDP 消息，可能为 {@link null}
     */
    void add(SocketAddress socketAddress, Ukcp ukcp, DatagramPacket msg);

    /**
     * 删除一个 kcp 连接对象
     *
     * @param ukcp {@link Ukcp} 对象
     */
    void del(Ukcp ukcp);

    /**
     * 获取所有的 kcp 连接对象
     *
     * @return {@link Ukcp}'s {@link Collection}
     */
    Collection<Ukcp> getAll();
}
