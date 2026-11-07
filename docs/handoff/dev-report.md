# 工单分类开发交接

## 本次改动

工单创建与分类任务在同一事务中落库。调度器扫描已提交的任务，通过带租户 ID、随机令牌和过期时间的租约认领；任务结束时锁定租约行，在同一短事务内完成工单版本条件写回及任务状态更新。异常会退避重试，达到上限后状态为 FAILED；模型不可用时规则兜底按一次已完成分类处理。任务至少执行一次，调用模型后、结果提交前进程退出可能重复调用。

认领 SQL 已按 `TicketClassifyJobRepository` 核对：`ready` 以普通 `SELECT` 查找 `attempts < 10` 且到期的 `PENDING`，或 `lease_until <= CURRENT_TIMESTAMP(3)` 的 `RUNNING`；`claim` 用相同条件及任务 ID、租户 ID 做原子 `UPDATE`。待处理任务的租约列可为 `NULL`，不影响按 `next_run_at` 入选；`RUNNING` 租约为 `NULL` 不满足过期条件。认领没有 `SKIP LOCKED`，`FOR UPDATE` 只用于写回前锁定有效租约。已认领 10 次且租约到期的任务由 `failExhausted` 转成 `FAILED`。完整原 SQL 见 `docs/learn/ticket-classification-讲解.md`。

工单新增 revision，编辑必须提交读取到的版本；人工编辑和分类写回均按版本条件更新并递增。完整表单中分类、优先级与原值相同时不会误标人工来源；实际修改分类时标记 MANUAL 并清除旧 AI 理由、耗时和兜底标记。标题或正文改变时，非人工结果失效并为新版本入队，已标记 MANUAL 的分类保留。手动重新分类绕过结果缓存，并在失败或版本冲突时向调用方返回错误。

状态接口在当前租户内先查询调用者按现有工单列表规则可见的工单，再返回当前版本的任务状态。现有工单模块的设计是同租户内按接口权限共享，不实现个人或部门角色数据范围隔离。工作线程每次执行前后清理租户与用户上下文，避免复用线程污染下一任务及审计身份。

MySQL 迁移按已有工单表、revision 字段、任务表及历史回填的顺序由 Liquibase master 包含，未修改已执行的历史 changeset。历史回填只选未删除、未结束且没有人工分类和 AI 结果的工单。PostgreSQL 对应增量脚本已提供，但现有工单初始建表及 PostgreSQL 全链路尚未完成，不应宣称已支持。

## 本次执行的验证

- 使用离线 Maven 与本机缓存的 Byte Buddy agent，运行 `TicketManualEditTest,TicketApplicationMySqlIT`：人工编辑单测 8 项通过；隔离 MySQL 8 与 Redis 7.2.8 上完整 Spring/MockMvc 测试 5 项通过，均无跳过。该次还经过相关模块的 Enforcer、Spotless、Checkstyle 和编译。
- Spring 应用测试覆盖真实 Liquibase master 首次执行和在已升级数据库再次执行、建单提交与回滚、过期任务恢复、多租户隔离、单线程复用与用户上下文清理、管理员真实登录及工单 HTTP 流程、匿名 401、无接口权限用户对列表/详情/任务状态的 403、版本冲突与规则兜底。
- 真实 Redis 7 测试前曾使用 Redis 5；由于其不支持当前 Redisson 使用的命令参数，真实登录失败。改用独立 Redis 7 后已重跑通过；没有把旧版本失败或跳过计为成功。
- MySQL 任务仓库和版本 SQL 的独立集成验证及 Redis 缓存集成验证另见 `test-report.md`。最终代码已执行完整后端 verify（工单模块 67 项通过、无跳过）及前端 lint、typecheck、build，详见 `test-report.md`。

完整应用测试需显式设置 `TICKET_FULL_STACK_TEST=true` 与 `TICKET_HTTP_TEST=true`，并设置指向本机专用测试库的 `TICKET_MYSQL_TEST_URL`、`TICKET_MYSQL_TEST_USER`、`TICKET_MYSQL_TEST_PASSWORD` 和隔离 Redis 7 的 `TICKET_REDIS_TEST_PORT`；不能指向业务数据库。示例命令在 `backend/` 下为：

```powershell
mvn -o -pl continew-server -am test "-Dtest=TicketManualEditTest,TicketApplicationMySqlIT" "-Dsurefire.failIfNoSpecifiedTests=false" "-DargLine=-javaagent:<本机 byte-buddy-agent 路径>"
```

当前环境中 Maven Wrapper 下载受网络限制，实际使用本机缓存的 Maven 3.9.16；上述 agent 参数用于避免本机 Mockito 自附加卡住。真实登录测试创建随机低权限账号和任务，验证完成后按自己的 ID 清理并注销；管理员用例恢复原密码并清理本次工单。测试库与 Redis 均应为可丢弃的独立实例。

## 未覆盖及剩余风险

- 已对真实 DeepSeek 运行一轮 30 条自建样本评测，结果和局限见 `reports/ticket-eval-2026-11-07.md`；没有记录 token 用量及实际费用。MockMvc 不等于真实浏览器或线上部署验证。
- 测试通过构造过期租约并再次扫描验证恢复路径，没有通过强制杀进程验证完整进程重启，也没有证明多实例高并发的全部故障情形。
- 目标部署仍需 Redis 7、MySQL 及正确的生产密钥/HTTPS 配置；页面或线上演示是否已部署到本次代码尚未验证。
- 工单与任务记录由异步调度器处理；达到重试上限后需用户按页面提示手动重新分类。角色级个人/部门数据权限和独立数据库租户模式不在当前实现范围。
