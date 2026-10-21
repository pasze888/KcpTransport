# 内嵌 kcp-netty 源码，而不是引 jar 依赖

MC 26.3 自带 Netty 4.2.16，而 kcp-netty 1.5.2 的现成 jar 在该 Netty 上运行期 NoSuchMethodError（`NioEventLoop UkcpServerChannel.eventLoop()`，协变返回描述符断裂）——只有针对 4.2 重新编译才修，上游不归我们控制。因此 30 个源文件搬进 `io.github.pasze888.kcptransport.kcp` 改包名内嵌，也不引依赖。

## Considered Options

- Maven 依赖 `com.github.szhnet.kcp:kcp-netty`：在 MC 的 Netty 上直接崩，出局。
- shadow 重定位：MC 整个网络栈跑在同一份 Netty 上，重定位会造出两份 EventLoop/ChannelOption，更糟，出局。
- 给上游提 patch 等发版：时间不可控，且 4.2 适配是上游排期外的改动。

## Consequences

- 7930 行第三方代码进了仓，每次重内嵌都要对账：分叉点用可 grep 的 `// KcpTransport:` 单行 marker 标注（移植 5 处 + 行为分叉 2 处），散文写在 THIRD-PARTY-NOTICES.md。
- 上游 bug/安全修复不会自动到达我们，要手动搬。
