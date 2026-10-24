/*
 * KCP transport adapted from kcp-netty (MIT, (c) 2017 Zheng Sun), package-info
 * https://github.com/szhnet/kcp-netty - upstream KCP algorithm: skywind3000/kcp (MIT)
 * Recompiled for Netty 4.2; see THIRD-PARTY-NOTICES.md. Do not edit unless re-vendoring.
 */
// KcpTransport: package renamed from kcp-netty's io.netty.* on re-vendor.
// KcpTransport: dropped io.netty.bootstrap.UkcpServerBootstrap and UkcpServerBootstrapConfig; they occupied Netty's own package namespace.
// KcpTransport: the objects of this package are the shell only; the state machine and session layer underneath them live in kcp.base and come from java-kcp.
/**
 * Netty channels that carry KCP, with the protocol itself in {@code kcp.base}.
 *
 * <p>These classes are the shell: they own the UDP socket, wire the session into a vanilla
 * pipeline, and turn session callbacks into channel events. None of them is a state machine.
 * {@code kcp.base} is the vendored java-kcp kernel and session layer they drive, and the only
 * place a KCP state machine exists.
 */
package io.github.pasze888.kcptransport.kcp;
