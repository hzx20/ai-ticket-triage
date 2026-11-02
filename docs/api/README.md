# 接口文档

本目录有三份产物，用途不同：

| 文件 | 用途 | 怎么用 |
| --- | --- | --- |
| [`ticket-api.md`](./ticket-api.md) | 工单模块 9 个接口的完整说明（参数表、示例、注意事项） | 在 GitHub 上直接读，或 Ctrl+F 搜字段名 |
| [`api-reference.html`](./api-reference.html) | 离线可交互的接口手册，单文件、无外部依赖 | 双击用浏览器打开，左侧选接口，右侧看参数并可一键复制 curl |
| [`openapi.json`](./openapi.json) | OpenAPI 3.1 规范文件，服务端真实导出（141 个路径 / 196 个接口操作、200 个数据模型） | 导入 Postman / Apifox / Insomnia，或喂给代码生成器 |

`openapi.json` 由本地启动的服务端 `GET /v3/api-docs` 原样导出，只有 `servers` 字段补了公网地址，其余未改动。

---

## 一、基本约定

### 1. Base URL

| 环境 | 地址 | 说明 |
| --- | --- | --- |
| 公网 | `http://134.175.137.18/api` | nginx 会把 `/api` 前缀剥掉再转发给后端 |
| 本地 | `http://127.0.0.1:8000` | 后端直连，无前缀 |

本文档中所有路径都写服务端路径（如 `/ticket/manage`）。走公网时要在前面加上 `/api`。

### 2. 鉴权

除登录、验证码接口外，其余接口都需要在请求头带访问令牌：

```
Authorization: Bearer <accessToken>
```

令牌由 `POST /auth/login` 返回，默认有效期 30 分钟，可用 `POST /auth/refresh` 静默续期（依赖 Cookie 中的 refresh_token）。

登录需要**图形验证码**，流程是两步：

1. `GET /captcha/image` 拿到 `{ uuid, base64 }`（base64 是验证码图片）
2. 人工看图输入 4 位字符，连同 `uuid` 一起提交给 `POST /auth/login`

验证码答案存在 Redis（键名 `CAPTCHA:{uuid}`），本地开发时可以直接读 Redis 取值，不必真的看图。

### 3. 响应包装

所有接口返回统一信封，**HTTP 状态码恒为 200**，真正的结果看 `code` 字段：

```json
{
  "code": "0",
  "msg": "ok",
  "success": true,
  "timestamp": 1793602685383,
  "data": {}
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `code` | string | `"0"` 表示成功，其余为业务错误码 |
| `msg` | string | 提示信息，可直接展示给用户 |
| `success` | boolean | 是否成功，等价于 `code == "0"` |
| `timestamp` | number | 服务端毫秒时间戳 |
| `data` | any | 业务数据，失败时为 `null` |

**这是最容易踩的一个坑**：未授权时 HTTP 依然返回 200，只有 `code` 是 `"401"`。所以前端拦截器不能只看 HTTP 状态码，必须判断 body 里的 `code`。

| code | 场景 | 实测响应 |
| --- | --- | --- |
| `"0"` | 成功 | `{"code":"0","msg":"ok","success":true,...}` |
| `"400"` | 参数校验失败 | `{"code":"400","msg":"标题不能为空","success":false,...}` |
| `"401"` | 未登录 / 令牌过期 | `{"code":"401","msg":"您的登录状态已过期，请重新登录","success":false,...}` |
| `"403"` | 已登录但缺少权限标识 | `{"code":"403","msg":"无此权限","success":false,...}` |
| `"1"` | 服务端异常 | `{"code":"1","msg":"服务器异常，请联系管理员","success":false,...}` |

### 4. 分页

分页接口用 `page` / `size` / `sort` 三个查询参数，返回 `{ list, total }`：

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `page` | int | 1 | 页码，从 1 开始 |
| `size` | int | 10 | 每页条数，上限 1000 |
| `sort` | string | - | 排序，格式 `字段,asc\|desc`，多个用数组传，如 `createTime,desc` |

### 5. 权限标识

每个接口都绑了权限标识，缺一个就 403。超级管理员的权限是通配符 `*:*:*`。

| 权限标识 | 对应操作 |
| --- | --- |
| `ticket:manage:list` | 分页查询 |
| `ticket:manage:get` | 查询详情 |
| `ticket:manage:create` | 新增工单 |
| `ticket:manage:update` | 修改工单 |
| `ticket:manage:delete` | 删除 / 批量删除 |
| `ticket:manage:export` | 导出 Excel |
| `ticket:manage:classify` | 重新分类（调模型） |
| `ticket:manage:statistics` | 统计指标 |

> 权限标识必须是**三段式**。两段的 `ticket:statistics` 匹配不上超管的 `*:*:*`，会一直 403 —— Sa-Token 的通配是按段数比对的，这是本项目实际踩过的坑。

---

## 二、字段字典

### 分类（`category`）

| 编码 | 名称 | 判定线索 |
| --- | --- | --- |
| `account` | 账号与权限 | 登录失败、密码、验证码、账号被锁、权限不足 |
| `fault` | 系统故障 | 页面报错、接口 500、无法访问、响应慢 |
| `feature` | 功能需求 | 希望新增功能、改进建议 |
| `data` | 数据问题 | 数据错误、统计对不上、导入导出失败 |
| `consult` | 使用咨询 | 怎么操作、在哪里找某个功能 |
| `other` | 其他 | 以上都不属于 |

大模型返回非法值时统一降级为 `other`，不会抛异常。

### 优先级（`priority`）

| 值 | 含义 | 判定标准 |
| --- | --- | --- |
| `1` | 低 | 不影响业务使用，如咨询、建议、界面优化 |
| `2` | 中 | 影响个人或单个功能，有替代方案 |
| `3` | 高 | 影响多人或核心业务，如生产不可用、全员无法登录、数据丢失 |

模型返回非法值时降级为 `2`（中），宁可让人多看一眼，也不让工单沉底。

### 状态（`status`）

| 值 | 含义 |
| --- | --- |
| `1` | 待处理（新建时默认） |
| `2` | 处理中 |
| `3` | 已解决 |
| `4` | 已关闭 |
