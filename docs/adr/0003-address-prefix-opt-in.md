# KCP 启用走地址前缀 opt-in，不做自动协商

玩家把服务器地址写成 `kcp;host:port` 才走 KCP；其余一切（含 Realms、QuickPlay、transfer、列表 ping 的传输选择）走 TCP。没有握手公告，没有探测超时，没有回退，没有每主机记忆。

## Considered Options

- **乐观盲探测**（直连 KCP 发 handshake，超时回退 TCP）：被本机实测与上游一致否决。对已关端口 ICMP port-unreachable 在 LAN 内 1ms 回弹（探针实测 `PortUnreachableException`），但跨公网防火墙普遍丢弃 unreachable 回包，探测要付满超时，还向每个 vanilla 服务器发垃圾 UDP 包。
- **状态 ping 公告**（QUICify 的 `QuicAnnouncement` / NetBridge 的 `"networks"`）：自动，体验好，但要往 ServerStatus 的 CODEC 里做手术、加客户端缓存，且依赖「玩家先在列表里 ping 过」。作为二期候选保留。
- **静态登记表**（解析前缀时记 `Set<host:port>`，connect 处查表）：枪毙。connect 收到的是解析后的 `InetSocketAddress`，hostname 可能已变 IP，key 对不上会静默失效。上游两家维护中的项目都没用表。

## Consequences

- 前缀必须在 DNS 之前剥掉（`ServerAddressResolver.SYSTEM` 直接 `InetAddress.getByName(getHost())`，带前缀必 Unknown host），且 `kcp://` / `kcp:` 两种写法被 Guava HostAndPort 判成端口、不可用——只有分号/空格类可行。
- 意图的传递采用 raknetify 形态：`ConnectScreen$1` 构造点解析前缀、置 `@Unique` 标志并 `@Mutable @Shadow @Final` 重写捕获的 `ServerAddress`，`run()` 里 `@WrapOperation` 包 `Connection.connect` 换 bootstrap。两段注入，一段不够。
- 列表条目带前缀时 ping 也走 TCP：pinger 侧只剥前缀、不换传输。
- 写了前缀但对端无 KCP 监听 = 连接失败（原版同款 30s ReadTimeoutHandler 兜底，`-D` 可调），这是特性不是 bug：前缀是玩家的显式决定。
