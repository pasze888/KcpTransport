# 每条 KCP 会话固定归属一个 event loop

状态：Accepted（2026-10-23 提出，2026-10-25 转正）。对应规格 [#12](https://github.com/pasze888/KcpTransport/issues/12)。转正门槛已满足：[#17](https://github.com/pasze888/KcpTransport/issues/17) 实现会话亲和、并发断言与同 loop 负控；[#18](https://github.com/pasze888/KcpTransport/issues/18) 实机带 `kcp;` 前缀进服成功，证据见 [verification/issue-18-kcp-prefix-join.md](../verification/issue-18-kcp-prefix-join.md)。[ADR-0004](0004-single-threaded-kcp-event-loop.md) 自本条起 superseded，其服务端单线程约束不再适用。

本文转正时留下的 NAT 重绑定缺口，已由 [#21](https://github.com/pasze888/KcpTransport/issues/21) 补上：会话路由键从「只按远端地址」扩展为「远端地址 + 会话号」双索引。见下文 [会话号的第二个索引](#会话号的第二个索引21)，那里同时说明了它为什么不破坏本文的会话亲和。

> **已被 [ADR-0009](0009-rollback-session-migration.md) 取代（2026-10-26，[#22](https://github.com/pasze888/KcpTransport/issues/22)）。** #21 的双索引已回退成单一地址表，路由键回到「只按远端地址」。本段与下面「会话号的第二个索引」一节保留原文作为决策历史，**不要照它实现**。

本文的前提随 [ADR-0007](0007-switch-base-to-java-kcp.md) 转 Accepted 而成立：底座把每个 `Ukcp` 的任务都收在一个 `IMessageExecutor` 接口后面。#17 已给服务端 child 接上 `NettyMessageExecutor(eventLoop())`；客户端 socket 与会话本来就共用其所属 loop，仍使用就地执行器。

ADR-0004 把 KCP 的 EventLoopGroup 钉死为 1 线程，前提是 kcp-netty 的父 channel 与 child 共用一条 loop：入站 `kcpInput`、定时 `kcpUpdate`、`fireChannelRead` 进子管道全在父 loop 上。代价是明确的容量天花板——那条 loop 不只跑 KCP 状态机，还要扛 framing、分片、压缩、AES 加密与包解码（原版的主线程漏斗只收拢了包处理这一层）。

底座换成 java-kcp 后（ADR-0007），会话状态有了被关进一条线程的**接口**：`Ukcp` 的读写任务都投给构造时传入的 `IMessageExecutor`，内核零锁。服务端入站、写、flush、关闭和定时 tick 全部收进 child 的所属 loop，才可以拆掉父子共用单线程的约束；只搬定时器不够。

## Considered Options

- **维持单 loop**：零改动，但容量天花板不变，换底座的收益只剩「上游还活着」。
- **给 `Kcp` 状态加锁**（kcp-go / KumoKyaku 路线）：能换多核，但热路径改锁、锁粒度难证，ADR-0006 已否决。
- **每会话 loop 亲和**（选定）：与 java-kcp 的设计意图一致，内核保持零锁；代价是父 channel 不再代跑子会话的状态机。

## Consequences

- 父 `UkcpServerChannel` 不再轮询驱动全部子会话；每个 child 在注册 loop 上构建会话，以 `scheduleAtFixedRate` 驱动 update/flush，关闭时取消任务。首包可能早于注册完成，child 用待投递队列接住它，注册完成后在所属 loop 排空。不搬共享 `HashedWheelTimer`，避免额外定时器线程投递。
- 父 channel 的会话表从 #21 起是两张 `ConcurrentHashMap`：地址索引（快路径）与会话号索引（迁移路径）。它们被 N 条线程访问——child 在自己的 loop 上关闭时删表，父 loop 在迁移时重新键入——再加上每个会话一个 `volatile` 的远端地址字段，这就是「会话亲和」付出**全部**的共享可变状态，且三处都是外壳元数据，不是内核状态。`smokeConcurrency` 的「3 客户端 × 10 轮不串包」继续守着它。 **（2026-10-26 起作废：见 [ADR-0009](0009-rollback-session-migration.md)，表只剩一张；每个会话的远端地址字段随之退回 `final`，不再需要 `volatile`。）**
- `smoke.KcpConcurrencySmoke` 的 `executorCount() == 1` 断言作废，现改为真实 UDP 夹具：生产等价的 2 条 session loop 上断言两条会话归属不同 loop，保留 3 客户端 × 10 轮并发回显不串包，并用 1 条 loop 的反向夹具证明该断言确实变红。
- 会话路由键自 #21 起不再只有远端地址：地址索引保持快路径，会话号索引让同一 conv 换了源地址后仍能找回会话（NAT 重绑定）。迁移在该数据报到达时立即生效，不设等待窗口（#21 的决定）。#21 之前那句「NAT 重绑定导致会话断开的问题另开票，本次不动」到此销账。 **（2026-10-26 起作废：见 [ADR-0009](0009-rollback-session-migration.md)，迁移已回退，这笔「销账」随之取消——换网即掉线重连。）**
- 从固定大小池里选一个稳定的所属 loop，不是为每个远端新建线程；池满后多条会话可共享 loop，但同一会话绝不跨 loop。生产暂取 2 条会话 loop，UDP 父通道独立使用 1 条 loop；线程数调优留给实测。并发冒烟也使用 2 条会话 loop，保留 3 客户端 × 10 轮不串包并断言至少两条会话归属不同 loop，1 条 loop 的负控必须被拒绝。

## 会话号的第二个索引（#21）

> **整节已被 [ADR-0009](0009-rollback-session-migration.md) 取代（2026-10-26，[#22](https://github.com/pasze888/KcpTransport/issues/22)）。** 双索引与迁移分支已随该篇删除。下面「三条实现约束」的第 1 条失去对象；第 3 条被**反转**（`remoteAddress()` 现在就该返回 `super.remoteAddress()`，因为地址不再移动，`AbstractChannel` 缓存的首值就是正确值）；第 2 条「删表按 value」不再是被迫的时序补丁，而是对并发关闭的普通防御，保留。本节保留原文，不重写。

本文原先只按远端地址认会话，于是源地址一变（手机切 Wi-Fi↔4G、路由器重拨换端口、CGNAT 重新映射）会话就找不回来，客户端还在重传而服务端已经不认识它，最后靠原版 30 秒读超时收场。`conv` 本来就是为「多会话共用一条 UDP 端口」设计的标识，我们却没拿它路由。

现在服务端维护**两张表**：远端地址索引是绝大多数数据报走的快路径（地址没变时行为与性能都不变），会话号索引是迁移路径——地址未命中时从报文头解出 conv 再查一次，命中即把会话搬到新地址。首包规则不变：未知 conv 且 `sn == 0` 才建会话，其余来自陌生对端的包仍然丢弃。

**它为什么不破坏会话亲和。** 亲和约束的是「KCP 状态机被哪条线程驱动」：内核的收发队列、定时 flush、重传时钟仍然只由 child 所属的那条 loop 碰。`remoteAddress` 不是状态机状态，它是「回包往哪发」的外壳元数据；父 loop 写、child loop 读，靠 `volatile` 发布。把这两件事分开看，亲和这条不变式一条没破——但它确实新增了一处跨 loop 的写，所以本文上文的共享状态清单已经补齐，不能再说「唯一」。

**三条实现约束，写在这里是因为它们都是踩过的。**

1. **迁移时序固定**：先改会话的地址字段，再重新键入地址索引，最后打日志。任何一步反过来，都会留出一个「查得到会话、但回包还发往旧地址」的窗口。
2. **删表按 value，不按 key**：关闭发生在 child 的 loop 上，迁移发生在父 loop 上；关闭时读到的 key 可能已被迁移改掉，按 key 删会删空，会话就永远挂在新地址键下变幽灵条目。`ConcurrentHashMap.values().remove(child)` 两处都这么删。
3. **`remoteAddress()` 不能返回 `super.remoteAddress()`**：`AbstractChannel` 缓存首次读到的值、之后不再读 `remoteAddress0()`，迁移后它仍报建档时的地址——表键按它删就删错，同一会话挂两个键。这是 #21 里唯一一个靠日志才挖出来的 bug，写在这里免得下次有人「顺手改回 super」。

**已知行为，不是缺陷。** conv 由客户端选定且不校验：两个客户端撞号时先到者保留 conv 索引、后来者只走地址索引（否则重绑定会迁错会话）；同一 conv 从两个地址同时发包时后到者赢得会话，合法玩家可能被来回踢——#21 明确决定不设等待窗口，这两条都是那个决定的直接后果。`smoke.KcpRebindSmoke` 正向用例证明迁移与回包到达新端口，两条负控证明「不同 conv 不劫持」与「未知 conv 的非首包仍被丢弃」。

