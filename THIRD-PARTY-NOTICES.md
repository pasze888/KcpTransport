# Third-Party Notices

This mod bundles source code derived from the following projects.

## java-kcp

- Project: java-kcp (https://github.com/l42111996/java-Kcp), module `kcp-base`
- License: Apache-2.0 — full text in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt)
- Copyright: Copyright (c) jinmiao (l42111996), as declared in the upstream `pom.xml`
- Vendored commit: `de301ffb7068118e6fe386ed84a9a2b33ab37ef9` (2026-08-12)
- Vendored files: `src/main/java/io/github/pasze888/kcptransport/kcp/base/**` — `Kcp`, `IKcp`,
  `KcpOutput`, `KcpConfig`, `KcpListener`, `Ukcp`, `User`, `IChannelManager`, `ReadTask`,
  `WriteTask`, `ChannelConfig`, plus the `internal/` (7 files) and `threadpool/` +
  `threadpool/netty/` (5 files) helper packages.
- Not vendored: `KcpServer`/`KcpClient`/`Abstract*`, the `*ChannelHandler` and
  `*ChannelManager` implementations, `Crc32Encode`/`Crc32Decode`, `FecOutPut`, `ScheduleTask`,
  `threadPool/disruptor`, `threadPool/order`, and the whole `kcp-fec` module. The `Ukcp*Channel`
  shells around this kernel are restructured from kcp-netty (see the next section); our own
  handler on top of them supplies the ticking that `ScheduleTask` used to provide.

### Modifications

Each vendored file carries the same provenance header and is otherwise changed only as listed here.

1. **Package renamed.** `kcp`, `internal` and `threadPool` became
   `io.github.pasze888.kcptransport.kcp.base` and its subpackages; no symbol was renamed.
2. **FEC and CRC32 removed.** Because `kcp-fec` is not vendored, `ChannelConfig` loses
   `fecAdapt`, `crc32Check`, `crc32Size` and their accessors, and `Ukcp` loses
   `fecEncode`/`fecDecode`, the constructor block that wrapped the output in `FecOutPut`, the
   FEC/CRC32 demultiplexing in `input()` (every datagram now goes straight into the state machine,
   which is what plain KCP expects), and the matching `release()`. `Ukcp` no longer calls
   `kcp.setReserved(headerSize)`, so the reserved header stays 0 instead of the FEC/CRC32 size.
3. **SNMP counters removed.** `com.backblaze.erasure.fec.Snmp` lives in `kcp-fec` and only feeds
   statistics; its increments are gone from `Kcp` (8 sites), `Ukcp` (2), `ReadTask` (1) and
   `WriteTask` (1). The wire format is untouched.
4. **`org.jctools` replaced with Netty.** `Ukcp` asked for `MpscLinkedQueue`; it now calls
   `PlatformDependent.newMpscQueue()`, the same contract implemented on Netty's own queue factory,
   so the vendored code needs no dependency beyond those Netty and Minecraft already ship.
5. **`internal/ReItrLinkedList` implements `Queue`, not `Deque`.** On JDK 21+ `Deque` extends
   `SequencedCollection`, whose `reversed()` return type conflicts with `List`'s, so the class
   does not compile as upstream wrote it. The 11 `Deque`-only methods that carried `@Override`
   lost the annotation; behaviour is unchanged and the class stays internal to the vendored code.
6. **Malformed datagrams are dropped, not fatal.** Upstream's `Ukcp#input` converts KCP's
   negative return codes into `IOException`, and the session's read task treats any throw as a
   close, so a port scan, a monitoring probe or a load-balancer health check aimed at the UDP
   port would kill a live session. `Ukcp#input` now drops the datagram and logs at debug level,
   matching the C original, where `ikcp_input` returns a negative code and the caller simply
   ignores it. This is the same divergence we carried for kcp-netty (where it answered
   [szhnet/kcp-netty#15](https://github.com/szhnet/kcp-netty/issues/15), "抛了KcpException，必须要
   断开连接吗"), retargeted at the kernel that now sits underneath. Covered by
   `src/smokeTest/java/smoke/KcpSmoke.java`, which feeds junk straight into a session.

The vendored kernel is covered by `src/smokeTest/java/smoke/KernelSmoke.java`, which drives the
state machine over an in-memory wire without any Netty channel.

## kcp-netty

- Project: kcp-netty (https://github.com/szhnet/kcp-netty)
- License: MIT
- Copyright: Copyright (c) 2017 Zheng Sun
- Vendored files: the Netty channel shells and the packet carrier, kept from upstream under
  `src/main/java/io/github/pasze888/kcptransport/kcp/` — `package-info`, `UkcpChannel`, `UkcpChannelConfig`,
  `UkcpChannelOption`, `UkcpClientChannel`, `UkcpClientChannelConfig`, `UkcpClientUdpChannel`,
  `UkcpServerChannel`, `UkcpServerChannelConfig`, `UkcpServerChildChannel`, the three
  `Default*ChannelConfig` implementations, `UkcpPacket`, `Consts` and `Utils`. Upstream's own
  state machine (`Kcp`), session (`Ukcp`), `KcpOutput`, `KcpMetric`, `KcpException` and the
  `internal/` helper package were deleted with the base swap (issue #16); `kcp.base` is the
  java-kcp kernel that replaced them.
- Modifications: package renamed; recompiled against Netty 4.2.x; `io.netty.bootstrap.UkcpServerBootstrap`
  and `UkcpServerBootstrapConfig` dropped (they occupy Netty's own package namespace);
  `isCompatible` relaxed from `NioEventLoop` to `IoEventLoop` in `UkcpServerChildChannel` and
  `UkcpClientChannel`. Upstream requires the concrete 4.1-era loop class, which on Netty 4.2 only
  the deprecated `NioEventLoopGroup` compatibility path produces; the interfaces are the same one
  (`IoEventLoop extends EventLoop`), so the channels now register on
  `MultiThreadIoEventLoopGroup` + `NioIoHandler` like any 4.2 channel (issue #11). The shells now
  hand their datagrams to and from a `kcp.base.Ukcp` session instead of driving a `Kcp` of their
  own, and the option surface shrank to the knobs that kernel actually has (see
  `UkcpChannelConfig`); the vanilla pipeline above them is unchanged.

## skywind3000/kcp

- Project: kcp (https://github.com/skywind3000/kcp)
- License: MIT
- The KCP algorithm implemented by java-kcp is a port of this C implementation.

## MIT License text

    MIT License

    Copyright (c) 2017 Zheng Sun

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
