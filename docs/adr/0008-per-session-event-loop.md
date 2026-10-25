# 每条 KCP 会话固定归属一个 event loop

状态：Accepted（2026-10-23 提出，2026-10-25 转正）。对应规格 [#12](https://github.com/pasze888/KcpTransport/issues/12)。转正门槛已满足：[#17](https://github.com/pasze888/KcpTransport/issues/17) 实现会话亲和、并发断言与同 loop 负控；[#18](https://github.com/pasze888/KcpTransport/issues/18) 实机带 `kcp;` 前缀进服成功，证据见 [verification/issue-18-kcp-prefix-join.md](../verification/issue-18-kcp-prefix-join.md)。[ADR-0004](0004-single-threaded-kcp-event-loop.md) 自本条起 superseded，其服务端单线程约束不再适用。

本文的前提随 [ADR-0007](0007-switch-base-to-java-kcp.md) 转 Accepted 而成立：底座把每个 `Ukcp` 的任务都收在一个 `IMessageExecutor` 接口后面。#17 已给服务端 child 接上 `NettyMessageExecutor(eventLoop())`；客户端 socket 与会话本来就共用其所属 loop，仍使用就地执行器。

ADR-0004 把 KCP 的 EventLoopGroup 钉死为 1 线程，前提是 kcp-netty 的父 channel 与 child 共用一条 loop：入站 `kcpInput`、定时 `kcpUpdate`、`fireChannelRead` 进子管道全在父 loop 上。代价是明确的容量天花板——那条 loop 不只跑 KCP 状态机，还要扛 framing、分片、压缩、AES 加密与包解码（原版的主线程漏斗只收拢了包处理这一层）。

底座换成 java-kcp 后（ADR-0007），会话状态有了被关进一条线程的**接口**：`Ukcp` 的读写任务都投给构造时传入的 `IMessageExecutor`，内核零锁。服务端入站、写、flush、关闭和定时 tick 全部收进 child 的所属 loop，才可以拆掉父子共用单线程的约束；只搬定时器不够。

## Considered Options

- **维持单 loop**：零改动，但容量天花板不变，换底座的收益只剩「上游还活着」。
- **给 `Kcp` 状态加锁**（kcp-go / KumoKyaku 路线）：能换多核，但热路径改锁、锁粒度难证，ADR-0006 已否决。
- **每会话 loop 亲和**（选定）：与 java-kcp 的设计意图一致，内核保持零锁；代价是父 channel 不再代跑子会话的状态机。

## Consequences

- 父 `UkcpServerChannel` 不再轮询驱动全部子会话；每个 child 在注册 loop 上构建会话，以 `scheduleAtFixedRate` 驱动 update/flush，关闭时取消任务。首包可能早于注册完成，child 用待投递队列接住它，注册完成后在所属 loop 排空。不搬共享 `HashedWheelTimer`，避免额外定时器线程投递。
- 父 channel 的会话表 `childChannelMap` 从此被 N 条线程访问。它已经是 `ConcurrentHashMap`（`UkcpServerChannel.java:73`，随 #14 提前换好），本次不再需要动它——这是「会话亲和」付出的唯一共享可变状态。
- `smoke.KcpConcurrencySmoke` 的 `executorCount() == 1` 断言作废，现改为真实 UDP 夹具：生产等价的 2 条 session loop 上断言两条会话归属不同 loop，保留 3 客户端 × 10 轮并发回显不串包，并用 1 条 loop 的反向夹具证明该断言确实变红。
- 会话路由键保持远端地址不变（`UkcpServerChannel.java:73` 的表键、`:363` 与 `:391` 的查表）；NAT 重绑定导致会话断开的问题另开票，本次不动。
- 从固定大小池里选一个稳定的所属 loop，不是为每个远端新建线程；池满后多条会话可共享 loop，但同一会话绝不跨 loop。生产暂取 2 条会话 loop，UDP 父通道独立使用 1 条 loop；线程数调优留给实测。并发冒烟也使用 2 条会话 loop，保留 3 客户端 × 10 轮不串包并断言至少两条会话归属不同 loop，1 条 loop 的负控必须被拒绝。

