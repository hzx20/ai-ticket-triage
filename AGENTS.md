# AGENTS.md

`ai-ticket-triage` 的项目级 AI 协作说明。**本文件只管跨端约定和协作流程**，前后端各自的工程规范在子目录里，不要在这里重复。

## 先读哪份

| 你要做的事 | 先读这份 |
| --- | --- |
| 改 Java 后端 | [`backend/AGENTS.md`](backend/AGENTS.md) |
| 改 Vue 前端 | [`frontend/AGENTS.md`](frontend/AGENTS.md) |
| 搞清楚整体怎么串、跑哪些命令 | 本文件 |

## 项目构成

单仓库双端，前端产物构建后由后端托管部署。

| 目录 | 是什么 |
| --- | --- |
| `backend/` | Maven 多模块后端，基于 ContiNew Admin 4.2.0（Spring Boot + MyBatis Plus） |
| `backend/continew-plugin/continew-plugin-ticket/` | 自研 AI 工单模块，本项目的核心业务代码 |
| `backend/continew-{common,system,extension,server}/` | 框架基座，非需求明确要求不动 |
| `frontend/` | Vue 3 + Vite + Arco Design，包管理用 pnpm 9 |
| `frontend/src/views/ticket/` | 工单页面 |
| `docs/` | GitHub Pages 文档站（api / images / index.html），只读 |
| `deploy/` | 部署相关 |

## 门禁速查

**后端**（在 `backend/` 下执行）：

```bash
./mvnw verify -Dspotless.check.skip=true
```

门禁链路：Enforcer → Spotless → Checkstyle → 单元测试 → SpotBugs。

**前端**（在 `frontend/` 下执行）：

```bash
pnpm lint && pnpm typecheck && pnpm build
```

## 本机环境的一个坑（必读）

这台机器 `git config core.autocrlf = true`，工作区检出的文件是 CRLF，而 Spotless 的 Eclipse 格式化器期望 LF。后果是：**直接跑 `./mvnw verify` 会在根本没改过的文件上报约 23 处格式违规**，纯属误报。

所以：

- 本地验证一律加 `-Dspotless.check.skip=true`，其余三道门禁和单元测试照常跑。
- **不要**跑 `./mvnw compile -Pformat` 或 `spotless:apply`，它会把 23 个无关文件重写成 LF，diff 全部污染。上游 `backend/AGENTS.md` 里推荐的 `-Pformat` 适用正常环境，本机不适用。

## 三 Agent 协作

三个角色定义在 `.claude/agents/`，串起来是一条流水线：

```
需求 → dev-builder（写代码 + 交接单）
     → qa-verifier（跑门禁 + 补边界 + 测试报告）
     → code-tutor（读 diff + 讲原理 + 学习笔记）
```

测试不通过就退回 `dev-builder` 重改，通过之后才进讲解环节。每一步都留文件，换一次对话、隔一周回来，读这三份就能接上进度。

| 角色 | 产出路径 |
| --- | --- |
| `dev-builder` | `docs/handoff/dev-report.md` |
| `qa-verifier` | `docs/handoff/test-report.md` |
| `code-tutor` | `docs/learn/<模块名>-讲解.md` |

## 交付纪律（强制）

- **一个逻辑单元的改动，对应一个 commit，且必须跑一遍测试。** 逻辑单元指一个能独立说清、能独立回滚的改动（修一个 bug、加一个接口、调一处逻辑），粒度过细会把历史切碎，过粗则回滚时连带无关改动。每个 commit 的 message 说清改了什么，方便追踪和回滚。不要攒一堆改动一次性提交，也不要改完不提交就交差。提交只到本地，push 仍按下面「不擅自 push」执行。
- **改完就要有测试。** 每个逻辑单元都必须补上对应的测试（新增功能写新测试，修 bug 先写能复现的用例）。交付给用户之前，所有门禁和测试必须全部真实跑通；没跑过、或跑挂了，不许交付，也不许写成「已验证」。

## 动手前的底线

- 开工先 `git status`。工作区可能已有未提交的改动，禁止 `git checkout -- .`、`git reset --hard`、`git stash` 把它们一起抹掉。
- 不删不是自己创建的文件。
- 不擅自 push、不擅自切分支。
- 没有真正跑过的命令，不写成「已验证」。
