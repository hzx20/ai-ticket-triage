# docs/handoff

开发与测试的过程留痕目录，由两个 agent 自动维护。

| 文件 | 谁写 | 内容 |
| --- | --- | --- |
| `dev-report.md` | `dev-builder` | 本次改了哪些文件、关键决策、真跑过的命令与结果、存疑点 |
| `test-report.md` | `qa-verifier` | 门禁执行结果、改动核对、新增边界用例、缺陷清单 |

两份都是**覆盖式**写入，始终只保留最新一次。历史记录看 git log。

## 关于 `-Dspotless.check.skip=true`

后端命令里带这个参数不是偷懒。本机 `core.autocrlf=true`，工作区 CRLF 与 Spotless 期望的 LF 不一致，未改动文件会被误报约 23 处格式违规。跳过它，Enforcer、Checkstyle、单元测试、SpotBugs 仍照常执行。

## 不希望进公开仓库的话

这两个目录会随 `docs/` 一起发布到 GitHub Pages。如果不希望交接单和学习笔记公开，把下面两行加进仓库根的 `.gitignore`：

```
docs/handoff/
docs/learn/
```
