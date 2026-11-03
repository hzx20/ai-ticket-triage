# AI 工单管理系统

[![ticket-tests](https://github.com/hzx20/ai-ticket-triage/actions/workflows/ticket-tests.yml/badge.svg)](https://github.com/hzx20/ai-ticket-triage/actions/workflows/ticket-tests.yml)

用户提交工单时不用自己选分类。大模型读一遍描述，就判定它属于哪个分类、有多紧急，判断理由和耗时一并落库。

模型是外部依赖，会超时、会限流、会欠费。所以这些都不影响工单入库：调用失败自动降级为关键词规则，工单照常提交，页面用「兜底率」把降级暴露出来，超过 30% 标红，避免模型悄悄挂了没人发现。

实测 4 条刻意避开关键词描述的工单，模型全部给出有效判定，其中 2 条的结论与关键词规则不同——规则只能靠字面词命中，模型能读懂「登不进去」「停在加载页」这类说法背后的语义。平均耗时约 1.6 秒，单条成本在 0.001 元量级。分类走异步，提交接口毫秒级返回，模型跑完再回填分类字段。同一份内容在 5 分钟内重复提交会命中 Redis 短缓存，直接复用上次结果，不再产生模型调用。

界面、用户角色、权限这些基础能力来自 [ContiNew Admin](https://github.com/continew-org/continew-admin)（Spring Boot 3.5 + Vue 3 后台框架）；分类器、异步任务、建表脚本、工单页面和生产部署编排是我写的。

线上地址：**http://134.175.137.18** 　账号密码不在公开仓库写明，避免被扫描器批量尝试；需要体验可来信索要，或按下方 [本地运行](#本地运行) 自行起一套。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./docs/images/architecture-dark.svg">
  <img src="./docs/images/architecture.svg" alt="部署架构：浏览器 → nginx → Spring Boot（含分类线程池）→ MySQL/Redis，外部依赖 DeepSeek">
</picture>

---

## 一句话说清做了什么

| 维度 | 内容 |
| --- | --- |
| 业务闭环 | 提交工单 → 模型判定分类与紧急程度 → 分流 → 人工处理 → 统计兜底率与耗时 |
| 我写的部分 | AI 工单插件（分类器、异步任务、Controller、实体、建表脚本）、前端工单页面、生产部署编排 |
| 借用底座 | ContiNew Admin v4.2.0（Spring Boot 3.5 + Vue 3 的通用后台框架），用户/角色/菜单/租户等基础能力 |
| 代码规模 | 新增后端 16 个类、1 个 Liquibase changeset、前端 2 个目录 |
| 线上状态 | 4 个容器全部运行，广州 · 2 核 4G · Ubuntu 24.04 |

---

## 核心链路

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="./docs/images/classify-flow-dark.svg">
  <img src="./docs/images/classify-flow.svg" alt="分类链路：提交落库 → afterCommit 异步 → 线程池 → 模型判定，失败降级为关键词规则">
</picture>

关键点是**提交接口不等模型**。落库并提交事务之后，用 `afterCommit` 回调把分类任务丢进线程池，接口毫秒级返回；模型跑完再把 `category` / `priority` / `aiReason` / `aiCostMs` 写回同一行记录。

如果做成同步，模型高峰期慢到 3 秒，提交按钮就得转 3 秒的圈。异步的代价是提交瞬间分类字段是空的，前端多刷一次列表 —— 对工单系统来说这个代价可以接受，用户关心的是「我提交成功了」，不是「分类立刻出现」。

---

## 四个值得说的设计取舍

### 1. 规则没有删掉，而是降级成兜底

大模型是外部依赖，会超时、会限流、会欠费。分类失败不能让工单提交失败，所以每一条失败路径都有出口：

| 异常场景 | 处理方式 |
| --- | --- |
| 没配 API Key | 直接走规则，不发起网络请求 |
| 调用抛异常 / 超时（连接 5s、读取 20s） | 捕获后走规则，理由里写明异常类型 |
| 返回内容带 markdown 代码块 | 截取首个 `{` 到末个 `}` 再解析，仍失败则走规则 |
| 返回的分类值不在枚举里 | `parse()` 降级为 `other`，不抛异常 |
| 返回的优先级非法 | 降级为「中」，宁可让人多看一眼也不让工单沉底 |

兜底不是隐藏的：每条工单有 `aiFallback` 标记，统计接口输出兜底率，**超过 30% 页面标红**，用来发现「模型悄悄挂了而没人知道」。

### 2. 事务与异步的竞态

第一版是 `super.create(req)` 之后直接 `submit(id)`，实测出现过 `工单不存在，跳过分类` 的日志 —— 异步线程在事务提交前就去查库，查不到。

修复方式是 `TransactionSynchronizationManager.registerSynchronization` 注册 `afterCommit` 回调。这种 bug 的表现是**偶发**的分类丢失，不修的话线上极难定位。

### 3. 线程池不注册成 Bean

把 `ThreadPoolTaskExecutor` 注册成 Spring Bean 会触发一个连锁反应：容器里已经有 `Executor` 类型的 Bean，Spring Boot 的 `TaskExecutionAutoConfiguration` 就不再创建默认的 `applicationTaskExecutor`，而框架里已有代码依赖它，应用直接启动失败。

同时 `@Async` 在这里也不可用 —— 同类自调用会绕过 AOP 代理，实际还是同步执行。

最终方案是类内部自持一个线程池实例（核心 2 / 最大 4 / 队列 200），拒绝策略用 `CallerRunsPolicy`：队列满了由提交线程自己跑，宁可慢一点也不丢任务。

### 4. 缓存只在结果可信时才写

分类结果按「租户 + 标题 + 正文」做 SHA-256 摘要当 key，TTL 300 秒，命中就直接复用、不再花一次模型调用。三处细节是刻意的：

**只有非兜底结果才写缓存。** 模型限流或超时时会产生兜底结果，如果把它缓存 5 分钟，这段时间内所有同内容工单都会用错误分类。宁可多花一次钱，也不能把故障期的错误结论固化下来。

**用 `SET key PROCESSING NX EX 60` 抢计算权，不是「先 get 再 set」。** 两条命令不是原子的，两个线程会同时判空、同时调模型，等于没防住。抢不到的一方不做任何等待，直接自己调模型——线程池只有 4 个线程，等待会把线程占住，多调一次模型最坏多花 0.001 元，占住线程可能让整条分类链路排队。

**Redis 出任何问题都退化为「直接调模型」。** 连不上、序列化失败、`StringRedisTemplate` Bean 不存在（用 `ObjectProvider` 拿，拿不到就是 null），全部只记 warn 不往外抛。缓存是加速器，不是依赖。

没有做语义相似召回（相似工单复用结果）。原因是 DeepSeek 没有 embedding 接口，走这条路要额外部署 BGE-M3，多一台机器和一套运维，换来的收益只是「表述不同但意思相同的工单也能命中」——在一个 5 分钟、命中率本来就有限的缓存上，这笔投入不划算。

---

## 实测数据

用 4 条**刻意避开关键词**的工单做对照，验证模型确实在起作用（2026-11-02，DeepSeek-V4.1-Flash）：

| 工单标题 | 关键词规则会判成 | 模型实际判定 | 耗时 |
| --- | --- | --- | --- |
| 登不进去 | 其他 / 中（**没有「登录」二字，规则命不中**） | **账号与权限 / 高** | 2162 ms |
| 点导出之后一直转圈 | 数据问题 / 中 | 数据问题 / 中 | 1938 ms |
| 这个表格能不能按部门分一下 | 功能需求 / 中 | **功能需求 / 低** | 1341 ms |
| 上周的数据好像少了几条 | 数据问题 / 中 | 数据问题 / 中 | 950 ms |

第一条最能说明问题：规则只能匹配「登录」这个字面词，模型能从「停在加载页」「整个组都用不了」读出影响范围，并把优先级提到「高」。

平均耗时约 1.6 秒，单条成本在 0.001 元量级（一次请求约 500 输入 token + 80 输出 token）。`aiReason` 是可解释性的落点，例如：

> 整个组多人卡在登录加载页无法进入，属登录失败且影响范围大

### 30 条标注集：规则对照模型（2026-11-03）

上面那 4 条只能说明「模型判得出」，说明不了「比规则好多少」。所以另做了一份 30 条的标注集（六类各 5 条，覆盖高/中/低优先级），同一批数据分别跑纯规则和完整链路。

跑了两轮，数字有一两条的波动（temperature 调到 0.1 也不是完全确定）：

| 维度 | 关键词规则 | 大模型 |
| --- | --- | --- |
| 分类准确率 | 22/30 = 73.3% | 25–26/30 = 83–87% |
| 优先级准确率 | 15/30 = 50.0% | 23–24/30 = 77–80% |
| 兜底率 | — | 0% |
| 平均耗时 | < 10 ms | 1374–1449 ms |

结论和预想的不一样，值得写清楚：**规则在分类上并不弱**。工单描述里只要出现「登录」「报错」「导出」这类标准词，规则就能命中，22 条里多数是这么判对的。真正的差距在**优先级**——规则只会「命中高危词就抬到高」，读不出影响范围是一人还是一组人，所以它一半的优先级判断是错的。

模型没判对的 10 条里，6 条是「分类对了、优先级差一档」；另外 4 条我事后看它给的理由，认为**是我的标注有问题**。比如「登不进去 / 整个组都卡在加载页、密码是对的」我标成 account，模型判 fault 并解释「属服务异常且影响多人」，这个判断比我的标注更准。所以 83–87% 这个数字偏保守。

评测集在 `src/test/resources/ticket-eval-set.jsonl`，每一条的标注都可以自己复核。

---

## 测试

模块内有 30 个单元测试，分四类，按「依赖从少到多」分层：

| 测试类 | 条数 | 覆盖什么 | 依赖 |
| --- | --- | --- | --- |
| `TicketClassifierTest` | 9 | 六类关键词规则映射、模型输出的 JSON 解析与六种降级路径 | 无 |
| `ClassifyResultTest` | 7 | 结果构造、理由截断、缓存值序列化往返、脏数据降级 | 无 |
| `ClassifyCacheKeyTest` | 7 | key 稳定性、租户隔离、拼接边界、超长截断 | 无 |
| `ClassifyCacheRedisTest` | 7 | 真实读写、TTL、`SET NX` 抢占、统计计数、Redis 不可用时的降级 | 本地 Redis |

前三个不依赖任何外部服务，构建时必跑；第四个会连 `127.0.0.1:6379`，连不上就整类跳过，不会卡住构建。GitHub Actions 里会起一个真 Redis 服务，所以 CI 上这 7 条是真跑的，不是跳过的。

**测试当场抓到一个真 bug。** 写 `nonJsonOutputShouldFallBackWithOriginalText` 这条用例时发现：模型输出解析失败后调用 `ruleBased(null, null, ...)`，标题和正文传的是空值。结果是「降级成功」了，但规则拿不到任何文本，所有解析失败的工单都被塞进「其他 / 中」——日志上看不出任何异常，只有对比两批数据的分类分布才能发现。已修复为传入原始标题正文。

### 准确率评测

`TicketEvalRunner` 是一份 30 条的带标注评测集（六类各 5 条，覆盖高/中/低优先级），会同时跑「纯规则」和「模型」两条链路做对照，输出分类准确率、优先级准确率、兜底率和平均耗时。

它默认不参与构建（会真调 30 次模型），需要手动触发：

```bash
cd backend/continew-plugin/continew-plugin-ticket
RUN_AI_EVAL=true DEEPSEEK_API_KEY=sk-xxx ../../mvnw test -Dtest=TicketEvalRunner
```

只报模型准确率没有意义，必须和规则基线对照——如果两者差不多，这个 AI 模块就不该存在。评测里有一条断言直接钉住这件事：模型命中数必须至少是规则基线的 1.2 倍。

---

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 后端 | Java 17 · Spring Boot 3.5.16 · MyBatis-Plus · Sa-Token · Liquibase |
| AI | Spring AI 1.1.8（OpenAI 协议兼容）· DeepSeek `deepseek-flash` · temperature 0.1 |
| 数据 | MySQL 8.0.42 · Redis 7.2.8（分类结果短缓存、命中计数） |
| 前端 | Vue 3 · TypeScript · Element Plus · Vite |
| 部署 | Docker Compose · nginx 1.27 · 腾讯云轻量应用服务器 |

模型接入用的是 OpenAI 兼容协议，换 `base-url` 和 `model` 就能切到别家，业务代码不用动。温度调 0.1 是因为分类任务要的是稳定复现，不需要发散。

---

## 目录结构

```
ai-ticket-triage/
├── backend/                        # 后端（ContiNew Admin 11 个模块 + 自研插件）
│   └── continew-plugin/
│       └── continew-plugin-ticket/ # ★ AI 工单插件
│           └── src/
│               ├── main/java/top/continew/admin/ticket/
│               │   ├── ai/         #   TicketClassifier（提示词/解析/兜底）、ClassifyCache（短缓存）
│               │   ├── controller/ #   TicketController：标准 CRUD + 重新分类 + 统计
│               │   ├── service/impl/ # TicketServiceImpl、TicketClassifyTask（线程池）
│               │   ├── model/      #   实体、请求、响应、查询条件
│               │   └── enums/      #   分类、优先级、状态
│               └── test/           #   30 个单测 + 准确率评测集（ticket-eval-set.jsonl）
├── frontend/                       # 前端（Vue 3）
│   └── src/views/ticket/           # ★ 工单管理页面
├── deploy/                         # ★ 生产部署
│   ├── docker-compose.yml          #   4 容器编排
│   ├── nginx.conf                  #   IP 直连版配置
│   ├── setup.sh                    #   服务器初始化与密钥生成
│   └── 部署说明.md                  #   运维命令、三个必须知道的坑、密钥清单
└── docs/
    ├── images/                     # 架构图
    └── api/                        # 接口文档（Markdown / 离线 HTML / OpenAPI）
```

---

## 本地运行

**前置**：JDK 17、Node.js 20+、pnpm、MySQL 8、Redis 7。

```bash
# 1. 建库（Liquibase 只建表不建库，库必须手工建）
mysql -u root -p -e "CREATE DATABASE continew_admin DEFAULT CHARSET utf8mb4;"

# 2. 后端
cd backend
./mvnw clean package -DskipTests
cd continew-server/target/app
DB_PWD=<你的MySQL密码> java -jar bin/continew-admin.jar --server.port=8000

# 3. 前端
cd frontend
pnpm install
pnpm dev        # http://localhost:5173
```

访问 `http://localhost:5173`，登录后在左侧菜单「AI 工单 → 工单管理」提交几条工单即可。

不配 `DEEPSEEK_API_KEY` 也能跑，只是所有工单都会走规则兜底（页面上兜底率 100%），这本身就是降级逻辑的现场演示。

---

## 部署

生产环境用 Docker Compose 编排 4 个容器：`nginx` + `Spring Boot` + `MySQL` + `Redis`。只有 nginx 对外暴露 80 端口，其余在内部网络。

```bash
cd deploy
cat 部署说明.md      # 密钥清单、运维命令、三个必须知道的坑
```

部署时踩到并记录下来的三个坑，都是文档里查不到的：

1. **前端 RSA 公钥是硬编码的**，和后端私钥必须配对。项目自带的 512 位演示公钥要换掉，只换后端会导致「密码解密失败」。
2. **`justauth.enabled` 开关是死的**，关掉会因缺少 `AuthRequestFactory` Bean 启动失败，即使不用第三方登录也必须置 `true`。
3. **没有 HTTPS 时 Cookie 必须降级**，`cookie-secure=true` 和 `__Host-` 前缀都要求 HTTPS，纯 HTTP 部署会导致刷新页面就掉线。

---

## 接口文档

**文档站**：https://hzx20.github.io/ai-ticket-triage/ —— 入口页有四份产物的导航，`api/` 直接是交互式接口手册，点开即用，不需要下载。

**服务端原生文档**：http://134.175.137.18/api/doc.html（nextdoc4j 界面，141 个路径 / 196 个接口操作、200 个数据模型，可按分组浏览，也能直接调一次看返回）。

仓库内的文件，用途各不相同：

| 文件 | 说明 |
| --- | --- |
| [`docs/api/api-reference.html`](./docs/api/api-reference.html) | 交互式手册源文件，单文件无外部依赖。线上见文档站；也可下载到本地双击，离线可用 |
| [`docs/api/ticket-api.md`](./docs/api/ticket-api.md) | 9 个接口的完整文字说明，GitHub 上直接可读 |
| [`docs/api/openapi.json`](./docs/api/openapi.json) | OpenAPI 3.1 规范，服务端真实导出，可导入 Postman / Apifox |

接口一览：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/ticket/manage` | 创建工单（异步触发分类） |
| GET | `/ticket/manage` | 分页查询（可按兜底标记筛选） |
| GET | `/ticket/manage/{id}` | 查询详情 |
| PUT | `/ticket/manage/{id}` | 修改（支持人工纠正分类） |
| DELETE | `/ticket/manage/{id}` | 删除单条 |
| DELETE | `/ticket/manage` | 批量删除 |
| POST | `/ticket/manage/{id}/classify` | 重新分类（同步） |
| GET | `/ticket/manage/statistics` | 兜底率、平均耗时等指标 |
| GET | `/ticket/manage/export` | 导出 Excel |

---

## 已知限制

写在这里，是因为它们比「已完成」更能说明下一步该做什么：

- **准确率只有离线评测集，没有线上真实分布**。30 条样本给出 83–87% 的分类准确率，但样本是自己造的，且事后复核发现至少 4 条标注本身有争议。要真正度量，得靠线上人工纠正记录按周回算。
- **缓存只覆盖完全相同的工单**。措辞不同但意思相同的工单仍会各调一次模型。做语义召回要额外的 embedding 服务（DeepSeek 没有 embedding 接口，自部署 BGE-M3 要多一台机器），在一个 5 分钟短缓存上性价比不划算，暂不做。
- **只打标签，没有自动派单**。分类结果目前靠人工看，没有按分类自动分配处理人。
- **第三方登录、定时任务、对象存储未启用**。分别是没配 client-id、没部署调度中心、用本地磁盘代替对象存储。
- **纯 HTTP 部署**。没有域名和证书，因此做了一处 Cookie 降级；补齐 HTTPS 后应当恢复标准配置。
- **接口文档对公网开放**。为了让人能自己点开看，关掉了 nextdoc4j 的生产屏蔽（`--nextdoc4j.production=false`），并在安全白名单放行了 `/doc.html` 与 `/*/api-docs/**`。放开的只是文档本身，`/ticket/manage` 等业务接口依然要求 token（实测未带 token 返回 401）。真实生产环境通常应当保留屏蔽，用内网访问或加 IP 白名单。

---

## 关于开源底座

后台框架来自 [ContiNew Admin](https://github.com/continew-org/continew-admin) 与 [continew-admin-ui](https://github.com/continew-org/continew-admin-ui)，Apache-2.0 授权，版权归原作者 Charles7c 及贡献者所有。本项目对其有少量修改（版本号、AI 相关配置、RSA 公钥、打包参数），修改范围见 [NOTICE](./NOTICE)。

在 `v4.2.0` 基础上新增的功能代码集中在 `backend/continew-plugin/continew-plugin-ticket/`、`frontend/src/views/ticket/`、`frontend/src/apis/ticket/`、`deploy/`、`docs/` 五个位置。

许可证：[Apache License 2.0](./LICENSE)
