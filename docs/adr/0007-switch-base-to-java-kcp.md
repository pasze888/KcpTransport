# 底座换成内嵌 java-kcp，只取内核与会话层

状态：Proposed（2026-10-23）。对应规格 [#12](https://github.com/pasze888/KcpTransport/issues/12)。切片 ①（单 loop 打通既有冒烟）落地后改 Accepted，届时在 ADR-0001 与 ADR-0006 顶部标注取代关系。

ADR-0006 横向复核九家实现后维持内嵌 kcp-netty，判据是「底座得在 Netty 里给出 Channel 级字节流」。这条判据现在不再成立：我们保留自己的 `Ukcp*Channel` 外壳（ADR-0002 不破），底座只需要提供**内核 + 会话层**——`Kcp` 状态机、按会话隔离的收/发队列、每会话一个 executor。kcp-netty 上游自 2023-09 停止维护，而 l42111996/java-kcp（Apache-2.0，HEAD 2026-08-12）恰好就是这个形状，它的每会话 executor 正是 ADR-0004 单线程天花板想要又造不出来的东西。

## Considered Options

- **只取内核**（`Kcp` + `KcpOutput` + `internal/*`）：接口全 public、零跨包，但会话亲和、会话表、定时器仍要自己造，而换来的内核与现有那份是同一套 skywind 算法，收益薄。
- **整库接入**（`KcpServer`/`KcpClient` 自己管 UDP socket）：复用最多，但 UDP socket、双栈绑定、mixin 注入点全要重做，且它钉 Netty 4.1。
- **引 Maven 中央的 jar**：`com.github.l42111996:kcp-base:1.6.2` 确实在（2026-05-28 发布），但它的类在**顶层包** `kcp`/`internal`/`threadPool`，两个模组同时引就撞车；且它靠 `kcp-fec` 的 `netty-all:4.1.104` 传递依赖，NeoForge 里得逐个 exclude。否决。
- **不换库，只在现有外壳里补会话亲和**：改动最小，但等于把 7930 行上游已停更的 fork 连同它的并发不变式继续留在账上，每多一处并行都要自己重新证明一遍。
- **内核 + 会话层，重新内嵌**（选定）：拿到现成的会话模型，外壳保持 Channel 形态。

## Consequences

- 搬进本仓的是 `kcp-base` 的 `kcp/`、`internal/`、`threadPool/netty`，包名改成 `io.github.pasze888.kcptransport.kcp`。ADR-0001 的搬运纪律继续适用：分叉点用 `// KcpTransport:` marker，散文进 THIRD-PARTY-NOTICES.md，处数不在文档里写死。
- 不搬 `kcp-fec`：CRC32/FEC/ackMask 一律不开，线上仍是标准 24 字节头。ADR-0006 的这条结论与「拿 kcp-go 当第三方参照对端」的做法都不变。
- 会带进一个非 optional 的 `jctools-core`（`Ukcp` 用 `MpscLinkedQueue`）。是内联那一个队列实现还是收作依赖，切片 ① 里定，两种都只影响几行 build.gradle。
- **#11 的 Netty 4.2 移植不作废**：那次改动全在 channel 层（`AbstractChannel`/`IoEventLoop`），两家的 `Kcp.java` 只碰 `ByteBuf`/`Allocator`/`Recycler`。
- **单帧上限没有改善**：java-kcp 消息模式仍是 255 片上限（`Kcp.java:483`），流模式作者自标「可能有bug还未测试」（`KcpConfig.java:52-59`）。ADR-0006 欠的两件零件继续欠着。
- 旧的 6000 余行 `kcp/` 连同内部分叉 marker 一并删除，靠 git 历史找回；不保留双实现开关。
