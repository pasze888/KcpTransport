# 每条 KCP 会话独占一个 event loop

状态：Proposed（2026-10-23）。对应规格 [#12](https://github.com/pasze888/KcpTransport/issues/12)。与 ADR-0007 同一批落地，Accepted 时把 ADR-0004 标为 superseded。

ADR-0004 把 KCP 的 EventLoopGroup 钉死为 1 线程，前提是 kcp-netty 的父 channel 与 child 共用一条 loop：入站 `kcpInput`、定时 `kcpUpdate`、`fireChannelRead` 进子管道全在父 loop 上。代价是明确的容量天花板——那条 loop 不只跑 KCP 状态机，还要扛 framing、分片、压缩、AES 加密与包解码（原版的主线程漏斗只收拢了包处理这一层）。

底座换成 java-kcp 后（ADR-0007），会话状态按构造被关在一条线程里：每个 `Ukcp` 持有一个自己的 executor，读写任务与定时任务全投到它上面，内核零锁。于是「每会话一条 loop」不需要任何锁就能成立，单线程约束失去理由。

## Considered Options

- **维持单 loop**：零改动，但容量天花板不变，换底座的收益只剩「上游还活着」。
- **给 `Kcp` 状态加锁**（kcp-go / KumoKyaku 路线）：能换多核，但热路径改锁、锁粒度难证，ADR-0006 已否决。
- **每会话 loop 亲和**（选定）：与 java-kcp 的设计意图一致，内核保持零锁；代价是父 channel 不再代跑子会话的状态机。

## Consequences

- 父 `UkcpServerChannel` 不再轮询驱动全部子会话的 `kcpUpdate`（现在是 `run()` 里的单一循环，`UkcpServerChannel.java:383-388`）；改由每个子会话在自己的 loop 上 `scheduleAtFixedRate` 驱动 update/flush。不搬 java-kcp 的共享 `HashedWheelTimer`：它多一次「定时器线程 → 会话线程」的跨线程投递，而每会话本来就有自己的 loop。
- 父 channel 的会话表 `childChannelMap` 从此被 N 条线程访问，必须换成线程安全的容器——这是本次唯一新增的共享可变状态。
- `smoke.KcpConcurrencySmoke` 的 `executorCount() == 1` 断言（`KcpConcurrencySmoke.java:63`）作废，分两步替换：单 loop 切片先只断言「3 客户端 × 10 轮并发回显不串包」，多 loop 切片再加「两条会话的 `eventLoop()` 不同」。
- 会话路由键保持远端地址不变（`UkcpServerChannel.java:59,307`）；NAT 重绑定导致会话断开的问题另开票，本次不动。
- 线程数与调度策略本次不拍板：先按「每会话一条 loop、从固定大小池里取」实现，实测后再定 N。

