# KCP 底座维持内嵌 kcp-netty，横向选型复核后不动

状态：**superseded by [ADR-0007](0007-switch-base-to-java-kcp.md)**（2026-10-25）。维持 kcp-netty 的结论不成立：判据「底座要在 Netty 里给出 Channel 级字节流」在保留自家外壳后不再必要。九家实现的横向事实、线上格式是标准 KCP、私有扩展不上线，这些仍然成立。

> 下面几处**只对旧底座成立**，不要照着它读现在的代码：①「全仓 grep 不到 crc/fec/ackmask」——新内核带 ackMask 代码（`base/KcpConfig.java:72` 等），只是默认 0 不上线；②「开流模式去掉单帧硬上限」——新内核的段数判定是无条件的（`base/Kcp.java:486`），流模式同样受 255 片限制，**这一项较旧底座是倒退**；③「服务端没设收发窗」——现在显式设了 32/128（`KcpServerListener.java:91-92`），不同于新内核默认的 32/32。欠着的两件零件本身仍然欠着。

2026-10-23 对 skywind3000/kcp、xtaci/kcp-go、l42111996/java-kcp、szhnet/kcp-netty、KumoKyaku/kcp（C#）、deepseeksss/kcp（Rust）、PhotonLibOS、MirrorNetworking/kcp2k 做了一次横向选型复核，结论是**底座不动**。本文回答的是 ADR-0001 之后的追问：「内嵌的这份是不是最好的底座，要不要换成别的」，以及「横向看下来，别人有的零件我们缺不缺」。

完整的逐项对照表与源码出处见 [docs/research/kcp-comparison.md](../research/kcp-comparison.md)，本篇只记录决策与理由。

## Considered Options

- **换成 java-kcp（l42111996）**：功能更全（自带 FEC、CRC32、加密、每会话一个 executor），但它**不提供 Netty Channel**——对外是 `Ukcp` 会话壳 + `KcpListener` 回调，底层是 `DatagramChannel` + handler。MC 的原版管道（`Varint21FrameDecoder` → 分帧 → `Connection`）挂在 Channel 上，要接进 java-kcp 就得自己写一层适配层，等于把 ADR-0002 刚删掉的东西重新造一遍；且它还停在 Netty 4.1.104（`pom.xml` netty.version），换过去要把 #11 的 4.2 移植重做一遍。否决。
- **换成 kcp-go / Rust / C# 版**：都不是 JVM 内，或不是 Channel 级形态，MC 管道挂不上去。否决。
- **等上游出 Netty 4.2 兼容版**：上游已实质停更——master 最后一次代码提交是 2023-09-26/27（此后只有 dependabot 分支在升 netty-codec），`pom.xml` 仍钉 netty 4.1.68，20 个 open issue 无人理。这条路事实上不存在。已并入 ADR-0001 的理由。
- **维持现状（选定）**：kcp-netty 是**唯一以 Netty Channel 形态提供字节流**的实现（`UkcpClientChannel extends AbstractChannel`、`UkcpServerChildChannel` 同），这是「原版管道一行不改」主张的根基。

## Consequences

- 选型标准被明确成一条可复用的判据：**底座的硬约束是「在 Netty 里给不给出 Channel 级字节流」，不是功能多寡**。功能再全，接不进原版管道就没意义。
- 我们内嵌的线上字节格式就是**标准 KCP**（包头 `IKCP_OVERHEAD = 24`，kcp/Kcp.java:87；解析在 `Kcp.input()`，kcp/Kcp.java:716；全仓 grep 不到 crc/fec/ackmask）。这条性质有两个用处：①不与 MC 的 `Varint21` 分帧抢字节；②可以拿 kcp-go 写一个最小 Go 客户端当**第三方参照对端**做交叉验证（第三方实现的 bug 不会和我们一起错）。
- 别人的私有扩展一律不抄：java-kcp 的 CRC32（整包前加 4 字节）、FEC 头（8 字节）、ackMask（24 字节 + N 位已收 sn 位图）——任意一项开启就与标准 KCP 不互通，且额外头部会与 MC 的分帧器在同一层抢字节。MC 包层已有 AES-CFB8 加密，KCP 层再加密是重复劳动。
- 明确否决的三条备选路线：C# 版 KumoKyaku/kcp 的加锁路线（实为 4 把结构锁、`flush()` 读侧也持锁，Netty 异步回调里锁粒度难证）；kcp-go 的每会话 mutex（同上）；KCP 层加密。
- **欠着的零件只有两件，且都在我们自己代码里，不用抄**：
  1. **开流模式去掉单帧硬上限**——`Kcp.send()` 的段数判定带 `!stream` 前置条件（kcp/Kcp.java:548），流模式下整条 127 段 / 约 174,749 字节的上限不生效；而 MC 的 `Varint21FrameDecoder` 是 `ByteToMessageDecoder`，本就是字节流语义，与流模式一致。
  2. **服务端窗口对齐客户端**——服务端 `childOption` 只设了 nodelay/interval/fastResend/nocwnd/mtu（KcpServerListener.java:83-87），没设 `UKCP_SND_WND`/`UKCP_RCV_WND`，于是沿用 vendored 默认 sndWnd=32 / rcvWnd=128（kcp/Kcp.java:74,79,138,140）；客户端是 1024/1024（KcpClientBootstrap.java:43-44）。KCP 协商取小值，服务端每 RTT 只能压 32 段 ≈ 44KB 给客户端。
- 多线程仍是未立项状态，判据未变：先量 `kcptransport-IO` 线程 CPU 与 RTT（单 loop 长期 >70% CPU 且 RTT 随并发上升才动手）。届时候选 A（会话→loop 亲和，参考 java-kcp 的每会话 executor，保内核零锁）仍是首选；C# 四把锁与 kcp-go 每会话 mutex 已被否。
