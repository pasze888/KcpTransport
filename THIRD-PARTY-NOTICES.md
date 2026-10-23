# Third-Party Notices

This mod bundles source code derived from the following projects.

## kcp-netty

- Project: kcp-netty (https://github.com/szhnet/kcp-netty)
- License: MIT
- Copyright: Copyright (c) 2017 Zheng Sun
- Vendored files: `src/main/java/io/github/pasze888/kcptransport/kcp/**`
- Modifications: package renamed; recompiled against Netty 4.2.x; `io.netty.bootstrap.UkcpServerBootstrap`
  and `UkcpServerBootstrapConfig` dropped (they occupy Netty's own package namespace);
  `ReItrLinkedList` changed to implement `Queue` instead of `Deque` to compile on JDK 21+
  (SequencedCollection `reversed()` return-type conflict);
  `isCompatible` relaxed from `NioEventLoop` to `IoEventLoop` in `UkcpServerChildChannel` and
  `UkcpClientChannel`. Upstream requires the concrete 4.1-era loop class, which on Netty 4.2 only
  the deprecated `NioEventLoopGroup` compatibility path produces; the interfaces are the same one
  (`IoEventLoop extends EventLoop`), so the channels now register on
  `MultiThreadIoEventLoopGroup` + `NioIoHandler` like any 4.2 channel (issue #11).

### Behaviour changes from the upstream library

These are deliberate divergences, made because the upstream behaviour is wrong for a game
transport. Both are covered by `src/smokeTest/java/smoke/KcpSmoke.java`.

1. **Dead link closes quietly instead of throwing.** Upstream raises
   `KcpException("State=-1 after update()")` and fires it into the channel pipeline when a
   segment has been retransmitted `dead_link` (default 20) times. The `-1` state itself comes
   from skywind3000/kcp, where it silently marks the peer as unreachable; for UDP that is the
   normal end of a session, not an error. Now the channel is closed without
   `exceptionCaught` (`Utils.closeQuietly`), so a disconnect does not leave a WARNING with a
   stack trace. Affected: `UkcpServerChannel` (2 sites), `UkcpClientChannel` (2 sites).

2. **Malformed datagrams are dropped, not fatal.** Upstream's `Ukcp#input` converts KCP's
   negative return codes into `IOException`, and the channel layer treats that as fatal. A
   port scan, a monitoring probe or a load-balancer health check aimed at the UDP port would
   therefore kill a live session (upstream issue #15, "抛了KcpException，必须要断开连接吗").
   `Ukcp#input` now drops the datagram and logs at debug level, matching the C original, where
   `ikcp_input` returns a negative code and the caller simply ignores it. A switch to do this
   was proposed by the upstream author in issue #15 but was never implemented as of v1.5.2.

## skywind3000/kcp

- Project: kcp (https://github.com/skywind3000/kcp)
- License: MIT
- The KCP algorithm implemented by kcp-netty is a port of this C implementation.

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
