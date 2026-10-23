# KcpTransport

MC 26.3 / NeoForge：给 MC 的连接加一路 KCP(UDP) 承载，双栈并存；字节流通道之上的原版管道不改。

不关掉 TCP：服务端原版 TCP 监听一行未改，KCP 由玩家写 `kcp;` 前缀单独启用（见 docs/adr/0005-dual-stack-not-pure-kcp.md）。

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for pasze888/KcpTransport (uses the `gh` CLI).
See `docs/agents/issue-tracker.md`.

### Triage labels

The five canonical triage roles use their default label strings. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: root `GLOSSARY.md` + `docs/adr/`. See `docs/agents/domain.md`.
