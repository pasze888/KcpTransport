# 每条 KCP 会话独占一个 event loop

状态：Proposed（2026-10-23）。对应规格 [#12](https://github.com/pasze888/KcpTransport/issues/12)。转正的门槛是「并行断言绿 + 实机带前缀进服一次」两条都满足，目前都还欠着：[#17](https://github.com/pasze888/KcpTransport/issues/17) 实现会话亲和、[#18](https://github.com/pasze888/KcpTransport/issues/18) 做实机验证。落地后转 Accepted 并把 ADR-0004 标为 superseded；在那之前 ADR-0004 仍然有效（`KcpServerListener.KCP_GROUP` 仍是单线程）。

本文的前提随 [ADR-0007](0007-switch-base-to-java-kcp.md) 转 Accepted 而成立：底座把每个 `Ukcp` 的任务都收在一个 `IMessageExecutor` 接口后面，只要给它一个真线程，会话就自带宽亲和。缺的是把它接上，不是再论证一次——现在两端传的仍是同一个就地执行的 `InlineMessageExecutor.INSTANCE`。

ADR-0004 把 KCP 的 EventLoopGroup 钉死为 1 线程，前提是 kcp-netty 的父 channel 与 child 共用一条 loop：入站 `kcpInput`、定时 `kcpUpdate`、`fireChannelRead` 进子管道全在父 loop 上。代价是明确的容量天花板——那条 loop 不只跑 KCP 状态机，还要扛 framing、分片、压缩、AES 加密与包解码（原版的主线程漏斗只收拢了包处理这一层）。

底座换成 java-kcp 后（ADR-0007），会话状态有了被关进一条线程的**接口**：`Ukcp` 的读写任务都投给构造时传入的 `IMessageExecutor`，内核零锁。今天传进去的还是就地执行的 `InlineMessageExecutor`（跑在调用线程上，没有任何亲和性），所以「每会话一条 loop」只剩接线这一步：把 executor 换成会话自己的 event loop，单线程约束就失去理由。

## Considered Options

- **维持单 loop**：零改动，但容量天花板不变，换底座的收益只剩「上游还活着」。
- **给 `Kcp` 状态加锁**（kcp-go / KumoKyaku 路线）：能换多核，但热路径改锁、锁粒度难证，ADR-0006 已否决。
- **每会话 loop 亲和**（选定）：与 java-kcp 的设计意图一致，内核保持零锁；代价是父 channel 不再代跑子会话的状态机。

## Consequences

- 父 `UkcpServerChannel` 不再轮询驱动全部子会话的 `kcpUpdate`（现在是 `run()` 里的单一循环，`UkcpServerChannel.java:330-357`）；改由每个子会话在自己的 loop 上 `scheduleAtFixedRate` 驱动 update/flush。不搬 java-kcp 的共享 `HashedWheelTimer`：它多一次「定时器线程 → 会话线程」的跨线程投递，而每会话本来就有自己的 loop。
- 父 channel 的会话表 `childChannelMap` 从此被 N 条线程访问。它已经是 `ConcurrentHashMap`（`UkcpServerChannel.java:73`，随 #14 提前换好），本次不再需要动它——这是「会话亲和」付出的唯一共享可变状态。
- `smoke.KcpConcurrencySmoke` 的 `executorCount() == 1` 断言（`KcpConcurrencySmoke.java:63`）作废，分两步替换：单 loop 切片先只断言「3 客户端 × 10 轮并发回显不串包」，多 loop 切片再加「两条会话的 `eventLoop()` 不同」。
- 会话路由键保持远端地址不变（`UkcpServerChannel.java:73` 的表键、`:363` 与 `:391` 的查表）；NAT 重绑定导致会话断开的问题另开票，本次不动。
- 线程数与调度策略本次不拍板：先按「每会话一条 loop、从固定大小池里取」实现，实测后再定 N。

