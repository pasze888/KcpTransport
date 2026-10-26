# 保持双栈监听，不做纯 KCP

服务端在同一端口号上同时提供 TCP 与 UDP：原版 TCP 监听一行未改（mixin 只在 `startTcpServerListener` 的 TAIL 追加一个 UDP 监听），KCP 由玩家在地址里写 `kcp;` 前缀单独启用（ADR-0003）。本篇正面回答「为什么不关掉 TCP」——此前 ADR-0001~0004 都没有回答它，后来人容易误读成「这个模组的目标是把 TCP 整体替换掉」。

## Considered Options

- **纯 KCP（服务端只监听 UDP，客户端一律走 KCP）**：一次砍掉四样东西——①没装模组的 vanilla 客户端完全进不来；②只说 TCP 的代理（Velocity / BungeeCord）转不动；③服务器列表 ping 走的是 TCP（`ServerStatusPinger` 直接 `Connection.connectToServer`，mc-reference/26.3/net/minecraft/client/multiplayer/ServerStatusPinger.java:63），纯 KCP 后列表拿不到 motd 与延迟；④Realms（`RealmsConnect` 同样直连 TCP，realms/RealmsConnect.java:54）与服务器转送（`ClientboundTransferPacket(host, port)` → `ConnectScreen.startConnecting`，client/multiplayer/ClientCommonPacketListenerImpl.java:353-364）会一起断。此外还会把全部流量压到 KCP 的单线程 event loop 上（ADR-0004 的容量天花板）。四样都是「不装本模组的人和设施」，与本模组无关，却被一并弄坏——否决。
- **双栈但默认走 KCP（不写前缀也用 UDP）**：省掉玩家写前缀这一步，但要为「对端没开 KCP」设计回退，而回退本身要付满超时（ADR-0003 已实测：跨公网防火墙普遍丢弃 ICMP unreachable 回包）。把「一次快速失败」换成「一次慢速失败」，不划算；且玩家失去了显式控制权。否决。
- **双栈 + 前缀 opt-in（选定）**：不写前缀 = 完全原版的 TCP 行为，模组对未参与的人零影响；写了前缀 = 玩家的显式决定，失败即失败（快速失败，ADR-0003）。两条路共用同一份 `ConnectionPipeline`，双栈几乎不带来重复实现成本。

## Consequences

- 客户端的 KCP 分支只挂在 `ConnectScreen` 这一个构造点（`ConnectScreenKcpMixin` 在 `<init>` 的 TAIL 置标志、在 `run()` 里 `@WrapOperation` 包 `Connection.connect`），因此**只有经过 `ConnectScreen` 的连接**才可能走 KCP——多人界面的手动连接，以及 26.3 里同样经 `ConnectScreen.startConnecting` 的 QuickPlay（`QuickPlay.java:110`）。**判据是地址里有没有前缀，不是连接怎么发起的**：命令行传来的地址若带前缀，QuickPlay 照样走 KCP（[#18](https://github.com/pasze888/KcpTransport/issues/18) 实测）。Realms 与服务器转送的地址由别处给出、不带前缀，列表 ping 只剥前缀不换传输，这三者留在 TCP。转送若要覆盖，得先给 `ClientboundTransferPacket` 之外的路径设计前缀载体。
  - 2026-10-26 订正：本条原写作「只有从多人游戏界面发起的连接才可能走 KCP……这个『只覆盖手动连接』的边界是当前实现的既成事实」。那句话与 [ADR-0003](0003-address-prefix-opt-in.md) 及 #18 的实测结果冲突，且与本句自带的括号说明（QuickPlay 走 `ConnectScreen.startConnecting`）自相矛盾——既然 QuickPlay 也过 `ConnectScreen`，它就不是「手动连接」能概括的。留此记录，免得有人按旧结论理解覆盖范围。
- 前缀必须在 DNS 之前剥掉（`ServerNameResolverKcpMixin` 在 `resolveAddress` 的 HEAD 改写入参）。ping 与 connect 都过这一个解析器，但 ping 只剥前缀、不换传输，所以**带前缀的服务器列表条目 ping 仍走 TCP**。
- 服务端两个监听共用同一个端口号（UDP 与 TCP 是不同协议空间，数字相同不冲突）；UDP 绑定失败只记一条 warn，TCP 服务照常——模组坏了不该让服务器起不来。
- AGENTS.md 首句原写作「把mc使用的 TCP 换成 KCP(UDP)」，读起来像整体替换，与本篇冲突，已改为「给 MC 的连接加一路 KCP 承载」。
