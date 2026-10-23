/*
 * KCP transport vendored from java-kcp (Apache-2.0, (c) jinmiao / l42111996), User.java
 * https://github.com/l42111996/java-Kcp - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Modified for KcpTransport; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
package io.github.pasze888.kcptransport.kcp.base;

import io.netty.channel.Channel;

import java.net.InetSocketAddress;

/**
 * Created by JinMiao
 * 2018/11/2.
 */
public class User {

    private Channel channel;
    private InetSocketAddress remoteAddress;
    private InetSocketAddress localAddress;

    private Object cache;

    public void setCache(Object cache) {
        this.cache = cache;
    }

    @SuppressWarnings("unchecked")
    public <T> T getCache() {
        return (T) cache;
    }

    public User(Channel channel, InetSocketAddress remoteAddress, InetSocketAddress localAddress) {
        this.channel = channel;
        this.remoteAddress = remoteAddress;
        this.localAddress = localAddress;
    }

    protected Channel getChannel() {
        return channel;
    }

    protected void setChannel(Channel channel) {
        this.channel = channel;
    }

    public InetSocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    protected void setRemoteAddress(InetSocketAddress remoteAddress) {
        this.remoteAddress = remoteAddress;
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    protected void setLocalAddress(InetSocketAddress localAddress) {
        this.localAddress = localAddress;
    }

    @Override
    public String toString() {
        return "User{" +
                "remoteAddress=" + remoteAddress +
                ", localAddress=" + localAddress +
                '}';
    }
}
