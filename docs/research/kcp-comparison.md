# 调研存档：各家 KCP 实现横向对照

调研日期 2026-10-23。**这是存档，不是决策**——结论见 [ADR-0006](../adr/0006-kcp-base-selection-review.md)。

## 一览

| 实现 | 语言/许可 | 内核 | 会话外壳 | 绑定形态 | 线程模型 | 活跃度 |
|---|---|---|---|---|---|---|
| skywind3000/kcp | C / MIT | 祖传内核 `ikcp.c` | 无 | 裸 UDP 回调 | 无线程，调用方驱动 `ikcp_update` | 16.9k★，2026-06 有提交 |
| xtaci/kcp-go | Go / MIT | skywind 移植 | `UDPSession`（sess.go） | `net.Conn`/`net.Listener` 替换 | 每会话 1 把 `sync.Mutex` + 每会话 1 个 readLoop goroutine | 4.5k★，2026-10 活跃 |
| l42111996/java-kcp | Java / Apache-2.0 | skywind 移植（`Kcp.java` 1596 行，ByteBuf 化） | `Ukcp` + 4 种 `IChannelManager`（conv/地址 × 服务端/客户端） | Netty **handler** 级（`DatagramChannel` + 回调，无 Channel 子类） | 每会话一个 `IMessageExecutor`（loop 亲和），内核零锁 | 535★，2026-08 |
| szhnet/kcp-netty | Java / MIT | skywind 移植 | `Ukcp` + `UkcpServerChildChannel` | Netty **Channel** 级 | 零锁，父子必须同 loop | 349★，**实质停更**（最后代码提交 2023-09-26/27，仅 dependabot 在动；20 个 open issue） |
| l42111996/kcp-netty | Java / MIT | 同上 | 同上 | Netty Channel 级 | 同上 | 0★，2020（szhnet 的上游祖先，同包名 `io.jpower`） |
| KumoKyaku/kcp | C# / MIT | skywind 移植 | — | — | **4 把结构锁**（snd_queue/snd_buf/rcv_buf/rcv_queue），运行期零分配 | 1006★，2023-11 |
| deepseeksss/kcp + tokio_kcp | Rust / MIT | skywind 直译 | — | tokio async | 单线程 runtime + 内部串行化 | 219★/200★ |
| PhotonLibOS `net/kcp.cpp` | C++ | 直接用 skywind ikcp 2.1.1 | `(conv, EndPoint)` 表 | 协程 `recv_loop` | 每会话一把 mutex；`PHOTON_ENABLE_KCP` 默认关 | 官方引擎内置 |
| MirrorNetworking/kcp2k | C# | — | — | 帧同步专用 | 主线程按帧 tick | 385★ |

「绑定形态」这一列是本次调研的**决定性判据**：只有 kcp-netty 系列提供 Netty Channel，MC 的原版管道才能直接挂上去。

## 关键机制逐项对照

| 机制 | skywind C | kcp-go | java-kcp | **我们的 fork** | 我们这边 |
|---|---|---|---|---|---|
| 线上字节格式 | 标准 24 字节头（conv/cmd/frg/wnd/ts/sn/una/len），**无 CRC** | 同 skywind | **三处私有扩展**：整包 CRC32(4B)、FEC 头(8B)、ackMask 位图(24B+N) | **就是标准 KCP**（grep 无 crc/fec/ackmask） | kcp/Kcp.java:87（`IKCP_OVERHEAD = 24`） |
| 与标准 KCP 互通 | 基准 | 互通（README 承诺） | 开扩展后**不互通**；且 CRC/FEC 头会与 MC 的 Varint21 分帧抢字节 | **互通** | kcp/Kcp.java:716（`Kcp.input()`） |
| 状态机线程安全 | 无 | 每会话 mutex（sess.go:755 `update()` 里 `s.mu.Lock()`） | 每会话 loop 亲和（读/写/定时全投同一 loop），核心零锁 | 零锁，**父子必须同 loop** | kcp/UkcpServerChannel.java:383（`run()` 驱动全部 `kcpUpdate`） |
| 驱动 update 的调用点 | 调用方 `ikcp_update` | `SystemTimedSched` 自调度堆 | `Kcp.update()` **是死代码**（生产路径无人调用），靠 `flush()` 返回的 minrto 驱动 | 父 channel 的 `run()` 定时器 | 同左 |
| 单帧段数上限 | 消息模式 128 段 | — | 同 | **127 段 ≈ 174,749 字节，超限直接抛 IOException 打死通道** | kcp/Kcp.java:548（`if (!stream && count >= IKCP_WND_RCV)`）→ kcp/Ukcp.java:100 |
| FEC | 无 | 有（Reed-Solomon，klauspost），头 6B+2B | 有（Backblaze RS），头 8B，**与 kcp-go 兼容** | 无 | — |
| 加密 | 无 | 包级 AES/TEA/3DES/Blowfish/Cast5/Salsa20 CFB + AEAD | 有 | 无（**不需要**：MC 包层已有 AES-CFB8） | — |
| 缓冲 | 静态数组 | `bufferpool.go` + `ringbuffer.go` | Recycler 池 | Netty `ByteBuf` 池 | — |
| 窗口默认值 | snd 32 / rcv 128 | snd 32 / rcv 32 | 同 skywind | **服务端沿用 32/128，客户端 1024/1024** | kcp/Kcp.java:74,79,138,140；KcpClientBootstrap.java:43-44 |
| 会话寻址 | 调用方 | conv + 远端地址 | conv / 地址 × 4 种 manager | **远端地址**（`childChannelMap` 按 InetSocketAddress） | kcp/UkcpServerChannel.java:307 |

## 几条容易踩的坑（跨语言借鉴时）

- **别只读锁点摘要，要读锁的实现**：C# 版 KumoKyaku/kcp 的 README 只说「Send/Input 内部有锁」，实际是 4 把结构锁，且 `flush()` 读侧也持 `snd_bufLock`——因为 `Parse_ack/Parse_una/Parse_fastack` 会改 `snd_buf`，而 `flush()` 正在遍历它。只锁「入队入口」锁不住状态机。
- **别只看类里有没有 `update()`**：java-kcp 的 `Kcp.update()` 是死代码，生产路径无人调用，拥塞窗口增长被挪进 `input()`、重传节奏交给 `flush()` 返回的 minrto。判断一个实现「是否自带定时驱动」要 grep 全部调用点。
- **评估互通性先看线上字节格式，再看线程归属**：顺序是 线上字节格式 → 线程/事件循环归属 → 会话寻址方式 → 才看功能丰富度。

## SO_REUSEPORT 的可行性（候选 B 的一手实测）

JDK 21 上 `DatagramChannel.setOption(StandardSocketOptions.SO_REUSEPORT, true)` 成功且可回读（Linux）。这是 Netty 之外的原生能力，不依赖 epoll transport；但它只解决「读并行」，会话状态仍需按 socket 隔离才不碰 CME。

## 出处

- 各实现仓库与关键文件均已只读克隆到 `/tmp/kcpcmp/`（java-kcp、kcp-netty-szhnet、kcp-netty-l42111996）与 `/tmp/photon_kcp/`；临时目录不保证存活，结论以上表的行号引用为准。
- 我们的实现位置：`src/main/java/io/github/pasze888/kcptransport/kcp/`（内嵌，14 处 `KcpTransport:` marker，`grep -rn "KcpTransport:" src/` 为准）。
