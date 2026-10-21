# 不在 KCP 通道与原版管道之间加适配层

设计稿里为 KCP 规划了一个「消息→字节流」适配器（KcpStreamAdapter），理由是 KCP 按段交付消息而原版 `Varint21FrameDecoder` 期待字节流。实测推翻了它：**适配器 0 行代码，整层删除。**

依据三条：`Varint21FrameDecoder` 本身就是 `ByteToMessageDecoder`，自带跨 channelRead 累积与 readerIndex 消费；kcp-netty 两条收路径（mergeSegmentBuf 与非 merge）交付消息后都 fire channelReadComplete，正是 ByteToMessageDecoder 需要的协议信号；smoke 用 MC 真实类把 splitter→FlowControlHandler→prepender 挂上 UkcpChannel 跑通，含 100KB（>70 段）大帧逐字节相等。

## Consequences

- 这条边界（字节流通道）上「先直连跑一次，再决定要不要写胶水」成为本仓规矩：跨协议边界的桥接层必须先被证伪，不能被假设。
- 唯一遗留约束：冒烟源集的 runtimeClasspath 要 += main.compileClasspath（MC 是 compileOnly，普通 JVM 默认拿不到 net.minecraft 类）。
