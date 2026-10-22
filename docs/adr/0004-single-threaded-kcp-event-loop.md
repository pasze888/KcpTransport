# KCP 的 NioEventLoopGroup 固定单线程

KCP 的 `EventLoopGroup` 在服务端（`KcpServerListener`）与客户端（`KcpClientBootstrap`）各只有一处，线程数**必须**是 1。这不是调优参数，是 kcp-netty 的并发前提。

kcp-netty 全层零锁：`UkcpServerChannel` 与 `Kcp` 里 `synchronized`、`ReentrantLock`、`Atomic*` 出现次数都是 0。它靠「server channel 与它的 child 共用同一个 event loop」串行化——入站 `kcpInput`/`kcpReceive`、`eventLoop().schedule` 驱动的 `kcpUpdate`、`fireChannelRead` 进子管道全在父 channel 的 loop 上。但 `UkcpServerChildChannel` 的父子只是逻辑关系，真正注册到哪个 loop 由 `ServerBootstrap` 的 childGroup 决定；多线程组下 child 的 `doWrite` 会跑在另一个 loop 上，与父 loop 的定时 `kcpUpdate` 并发改同一个 `Kcp`，`Kcp.flush` 遍历发送队列时被另一线程改动，抛 `ConcurrentModificationException`。实测表现是带 `kcp;` 前缀进服能过登录，在 configuration 阶段（大突发）断连：客户端 30s 报连接超时，服务端记 `lost connection: Disconnected`。

这个约束没有被上游强制，**所以「上游示例就是这么写的」不构成理由**：kcp-netty 自己的 `EchoServer` 用的是 `new NioEventLoopGroup()`（2×核数）。上游是潜在缺陷，低负载 echo 撞不上，真实 MC 登录撞得上。

## Considered Options

- **给 Kcp 状态加锁**（学 kcp-go 每会话一把 `sync.Mutex`）：能换多核，但要改 send/flush/input 热路径，Netty 异步模型下锁粒度难拿捏（一条 `writeAndFlush` 链路上有多个回调点），风险与收益不成比例。
- **每连接独立 socket + 独立 event loop**（学客户端 `UkcpClientChannel` 的形态）：天然并行，但服务端架构改动大，且放弃「一个端口一个 UDP socket」的端口语义。
- **把 `updateChildKcp` 调度到 child 自己的 loop 上**：只挪了定时器，入站 `kcpInput` 与 pipeline 触发本就在父 loop，状态仍被两个线程碰——治不了根。
- **单线程组**（选定）：与 kcp-netty 的设计意图一致，父子同 loop，天然串行，零改动。

## Consequences

- 所有 KCP 连接共享一个 event loop，这是明确的容量天花板（非 CPU 密集型，瓶颈在 `Kcp.flush` 与分片；实测单连接持续在线 4 分 40 秒、sn 到 5871 无异常）。容量改进另开票，不要靠调大线程数「优化」。
- 不变式落两处，缺一不可：`KcpServerListener.KCP_GROUP` 上的注释说明为什么不能改；`smoke.KcpConcurrencySmoke` 用反射读该字段断言 `executorCount() == 1`（行为测试抓不到这类回归），外加 3 客户端 × 10 轮并发回显验证多会话复用同一 socket 不串扰。
- 已实测该断言可变红：把线程数改成 2 后冒烟报 `found 2 threads` 并失败。
