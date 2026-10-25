# KCP 的 NioEventLoopGroup 固定单线程

状态：Accepted（历史约束），**将被 [ADR-0008](0008-per-session-event-loop.md) superseded**。2026-10-25，[#17](https://github.com/pasze888/KcpTransport/issues/17) 已接上服务端会话亲和并通过并发冒烟，下面的服务端单线程要求不再描述当前实现；ADR-0008 仍待 [#18](https://github.com/pasze888/KcpTransport/issues/18) 实机验证后正式转正。

> 以下保留作历史。#17 已删除父通道统一 `run()` 和服务端线程数反射断言：父通道只做地址分发，child 的读、写、flush、关闭和定时 tick 都在其所属 loop。生产暂用一个 UDP loop + 两个会话 loop 的固定池，容量定参另做实测；客户端仍在自己的单 loop 上运行。

历史实现的 `EventLoopGroup` 在服务端（`KcpServerListener`）与客户端（`KcpClientBootstrap`）各只有一处，线程数**必须**是 1。这不是调优参数，是 kcp-netty 的并发前提；#17 已由 java-kcp 会话亲和取代服务端的这条约束。

kcp-netty 全层零锁：`UkcpServerChannel` 与 `Kcp` 里 `synchronized`、`ReentrantLock`、`Atomic*` 出现次数都是 0。它靠「server channel 与它的 child 共用同一个 event loop」串行化——入站 `kcpInput`/`kcpReceive`、`eventLoop().schedule` 驱动的 `kcpUpdate`、`fireChannelRead` 进子管道全在父 channel 的 loop 上。但 `UkcpServerChildChannel` 的父子只是逻辑关系，真正注册到哪个 loop 由 `ServerBootstrap` 的 childGroup 决定；多线程组下 child 的 `doWrite` 会跑在另一个 loop 上，与父 loop 的定时 `kcpUpdate` 并发改同一个 `Kcp`，`Kcp.flush` 遍历发送队列时被另一线程改动，抛 `ConcurrentModificationException`。实测表现是带 `kcp;` 前缀进服能过登录，在 configuration 阶段（大突发）断连：客户端 30s 报连接超时，服务端记 `lost connection: Disconnected`。

这个约束没有被上游强制，**所以「上游示例就是这么写的」不构成理由**：kcp-netty 自己的 `EchoServer` 用的是 `new NioEventLoopGroup()`（2×核数）。上游是潜在缺陷，低负载 echo 撞不上，真实 MC 登录撞得上。

## Considered Options

- **给 Kcp 状态加锁**（学 kcp-go 每会话一把 `sync.Mutex`）：能换多核，但要改 send/flush/input 热路径，Netty 异步模型下锁粒度难拿捏（一条 `writeAndFlush` 链路上有多个回调点），风险与收益不成比例。
- **每连接独立 socket + 独立 event loop**（学客户端 `UkcpClientChannel` 的形态）：天然并行，但服务端架构改动大，且放弃「一个端口一个 UDP socket」的端口语义。
- **把 `updateChildKcp` 调度到 child 自己的 loop 上**：只挪了定时器，入站 `kcpInput` 与 pipeline 触发本就在父 loop，状态仍被两个线程碰——治不了根。
- **单线程组**（选定）：与 kcp-netty 的设计意图一致，父子同 loop，天然串行，零改动。

## Consequences

- 历史实现的所有 KCP 连接共享一个 event loop，这是明确的容量天花板（非 CPU 密集型，瓶颈在 `Kcp.flush` 与分片；实测单连接持续在线 4 分 40 秒、sn 到 5871 无异常）。
- 历史不变式曾由 `KcpServerListener.KCP_GROUP` 上的注释和 `smoke.KcpConcurrencySmoke` 的反射断言共同钉住；#17 已删除该反射断言，改由会话 loop 归属与同 loop 负控覆盖。
