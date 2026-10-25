# Issue #18：实机用 `kcp;` 前缀进服

日期：2026-10-25。对应 [#18](https://github.com/pasze888/KcpTransport/issues/18)，父规格 [#12](https://github.com/pasze888/KcpTransport/issues/12)。

## 环境

| 项 | 值 |
|---|---|
| 系统 | Arch Linux，无独显；Mesa llvmpipe 软件渲染，OpenGL 4.6 Core Profile |
| Java | OpenJDK 25（Gradle toolchain） |
| 游戏 | Minecraft 26.3 / NeoForge 26.3.0.90 |
| Netty | 4.2.16.Final |
| 服务端 | `./gradlew runServer`，独立 `run-server/` 目录，`online-mode=false`、`white-list=false` |
| 客户端 | `./gradlew runClient`，地址 `kcp;127.0.0.1:25565` |

客户端地址通过启动参数 `--quickPlayMultiplayer` 注入；该参数与手动在多人游戏界面输入都会进入 `ConnectScreen.startConnecting` → `ConnectScreen$1`（前缀解析与连接替换的注入点）。本次只跑了 QuickPlay 驱动，手动 GUI 输入未单独复测。

## 证据（已随提交留档）

- `issue-18-listeners.txt`：同配置服务端独立启动后（10:29:55）`ss -lunpt` 的 UDP/TCP 监听。
- `issue-18-server-excerpt.log`：服务端日志摘录（监听、会话建立、登录、加入）。
- `issue-18-client-excerpt.log`：客户端日志摘录（前缀地址、KCP 选择、世界渲染）。
- `issue-18-kcp-prefix-join.md`：本文件；原始日志位于被 gitignore 的 `run/`、`run-server/`，摘录已复制进仓库。

服务端摘录：

```text
[10:25:22.772] KCP transport listening on udp/0:0:0:0:0:0:0:0:25565
[10:25:29.982] Done (7.174s)! For help, type "help"
[10:26:42.607] KCP session established: remote=/127.0.0.1:45773, conv=866839120
[10:26:57.520] Dev[/127.0.0.1:45773] logged in with entity id 9 at (5.5, 112.0, -6.5)
[10:26:57.667] System chat: Dev joined the game
```

客户端摘录：

```text
[10:26:38.720] [Render thread/INFO] [net.minecraft.client.gui.screens.ConnectScreen/]: Connecting to kcp;127.0.0.1, 25565
[10:26:42.029] [Server Connector #1/INFO] [io.github.pasze888.kcptransport.KcpTransport/]: Connecting to localhost/127.0.0.1:25565 over KCP (UDP)
[10:27:35.539] [Render thread/INFO] [net.minecraft.client.renderer.DynamicGpuDataStorageMapped/]: Resizing Chunk Sections ...
```

进服期间 `ss -unp` 的 UDP 连接（源端口与服务端会话记录的 remote 端口一致）：

```text
0      0      [::ffff:127.0.0.1]:45773 [::ffff:127.0.0.1]:25565
```

## 判定

- **UDP 监听存在**：`issue-18-listeners.txt` 同时列出 `udp UNCONN *:25565` 与 `tcp LISTEN *:25565`，双栈都在。
- **服务端记录登录**：`Dev[/127.0.0.1:45773] logged in` 与 `Dev joined the game`。
- **客户端确实在 KCP 通道上**：客户端那行只证明「选择了 KCP 通道」（连接失败时同样会打印）；真正证据是服务端 `KCP session established`——该行只会在 `UkcpServerChannel` 收到某个远端的第一段 KCP 报文（`sn==0`）并据此创建会话时出现，TCP 登录不会产生它——以及服务端记录的 remote 端口与客户端 UDP socket 源端口一致（45773）。
- **无并发修改异常、无断线**：进服后静置约 87 秒（10:26:57 → 10:28:24）执行：

  ```text
  grep -ac ConcurrentModificationException run-server/logs/latest.log run/logs/latest.log
  run-server/logs/latest.log:0
  run/logs/latest.log:0
  grep -acE "lost connection|Disconnected" run-server/logs/latest.log   → 0
  grep -acE "lost connection|Disconnected" run/logs/latest.log          → 0
  ```

  客户端在此期间持续渲染区块；服务端的 `Can't keep up!` 是 llvmpipe 软件渲染下的 tick 落后告警，与传输无关。

## 备注

- 首次尝试被 `white-list=true` 拒绝（`Dev lost connection: You are not white-listed on this server!`）。那条记录本身也证明 KCP 会话已建立并走完登录协商；关闭白名单后重试得到上面的结果。
- 验证使用的 `run-server/` 目录与临时 `build.gradle` 改动（QuickPlay 参数、独立 gameDir）均已删除/还原，未进入提交。