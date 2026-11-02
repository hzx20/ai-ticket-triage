# 工单模块接口

> 服务端路径前缀 `/ticket/manage`，走公网时前面再加 `/api`。
> 所有接口需要 `Authorization: Bearer <accessToken>`，响应统一包在 `{code, msg, success, timestamp, data}` 里。
> 通用约定（错误码、分页、权限标识）见 [README.md](./README.md)。

## 接口一览

| # | 方法 | 路径 | 说明 | 权限标识 |
| --- | --- | --- | --- | --- |
| 1 | POST | `/ticket/manage` | 创建工单（异步触发 AI 分类） | `ticket:manage:create` |
| 2 | GET | `/ticket/manage` | 分页查询 | `ticket:manage:list` |
| 3 | GET | `/ticket/manage/{id}` | 查询详情 | `ticket:manage:get` |
| 4 | PUT | `/ticket/manage/{id}` | 修改工单（含人工纠正分类） | `ticket:manage:update` |
| 5 | DELETE | `/ticket/manage/{id}` | 删除单条（逻辑删除） | `ticket:manage:delete` |
| 6 | DELETE | `/ticket/manage` | 批量删除 | `ticket:manage:delete` |
| 7 | POST | `/ticket/manage/{id}/classify` | 重新分类（同步调模型） | `ticket:manage:classify` |
| 8 | GET | `/ticket/manage/statistics` | 统计指标 | `ticket:manage:statistics` |
| 9 | GET | `/ticket/manage/export` | 导出 Excel | `ticket:manage:export` |

其中 1～6、9 由框架的标准 CRUD 机制生成（注解 `@CrudRequestMapping` 声明需要哪些），7、8 是手写的自定义接口。

---

## 1. 创建工单

```
POST /ticket/manage
```

**请求体**

| 字段 | 类型 | 必填 | 约束 | 说明 |
| --- | --- | --- | --- | --- |
| `title` | string | 是 | ≤ 100 字 | 标题 |
| `content` | string | 是 | ≤ 2000 字 | 问题描述 |
| `category` | string | 否 | ≤ 30 字 | 分类编码。留空则由 AI 判定；传入则以传入值为准 |
| `priority` | int | 否 | 1/2/3 | 优先级。留空则由 AI 判定 |
| `status` | int | 否 | 1~4 | 状态，默认 `1`（待处理） |
| `assigneeId` | int64 | 否 | - | 处理人 ID |
| `handleRemark` | string | 否 | ≤ 500 字 | 处理备注 |

```bash
curl -X POST 'http://134.175.137.18/api/ticket/manage' \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "登不进去",
    "content": "输入账号之后一直停在加载页面，同事也遇到同样的情况，整个组都用不了"
  }'
```

**响应**

```json
{
  "code": "0",
  "msg": "ok",
  "success": true,
  "timestamp": 1793602454770,
  "data": { "id": 12 }
}
```

**注意：这个接口返回时，分类字段还是空的。**

分类在事务提交后交给 `ticket-classify` 线程池异步执行，所以创建接口是毫秒级返回，`category` / `priority` / `aiReason` 要等 1～3 秒才写回。实测模型耗时 0.9～2.2 秒。

调用方有两种处理方式：

- 前端在提交成功 2 秒后自动刷新一次列表（本项目采用的做法）
- 直接调第 7 个接口 `POST /ticket/manage/{id}/classify`，它是同步的，返回时分类已经写回

---

## 2. 分页查询

```
GET /ticket/manage
```

**查询参数**

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 否 | 关键词，同时模糊匹配 `title` 和 `content` |
| `category` | string | 否 | 分类编码，精确匹配 |
| `priority` | int | 否 | 优先级，精确匹配 |
| `status` | int | 否 | 状态，精确匹配 |
| `aiFallback` | boolean | 否 | 是否走了规则兜底。传 `true` 可筛出所有「模型没判成功」的工单 |
| `page` | int | 否 | 页码，默认 1 |
| `size` | int | 否 | 每页条数，默认 10，上限 1000 |
| `sort` | string | 否 | 排序，如 `createTime,desc` |

```bash
curl 'http://134.175.137.18/api/ticket/manage?keyword=登&aiFallback=false&page=1&size=10&sort=createTime,desc' \
  -H 'Authorization: Bearer <accessToken>'
```

**响应**（示例取自 2026-11-02 实测数据）

```json
{
  "code": "0",
  "msg": "ok",
  "success": true,
  "timestamp": 1793602500100,
  "data": {
    "total": 4,
    "list": [
      {
        "id": 4,
        "createUserString": "超级管理员",
        "createTime": "2026-11-02 14:36:12",
        "updateUserString": "超级管理员",
        "updateTime": "2026-11-02 14:36:13",
        "disabled": false,
        "title": "登不进去",
        "content": "输入账号之后一直停在加载页面，同事也遇到同样的情况，整个组都用不了",
        "category": "account",
        "categoryName": "账号与权限",
        "priority": 3,
        "status": 1,
        "statusName": "待处理",
        "assigneeId": null,
        "assigneeName": null,
        "handleRemark": null,
        "aiReason": "整个组多人卡在登录加载页无法进入，属登录失败且影响范围大",
        "aiFallback": false,
        "aiCostMs": 2162
      },
      {
        "id": 3,
        "createUserString": "超级管理员",
        "createTime": "2026-11-02 14:36:09",
        "updateTime": "2026-11-02 14:36:10",
        "disabled": false,
        "title": "点导出之后一直转圈",
        "content": "昨天还能用，今天点完就没反应了，等了十分钟也没文件下来",
        "category": "data",
        "categoryName": "数据问题",
        "priority": 2,
        "status": 1,
        "statusName": "待处理",
        "aiReason": "导出功能无响应，属数据处理环节异常，影响单个功能",
        "aiFallback": false,
        "aiCostMs": 1938
      }
    ]
  }
}
```

**字段说明**

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | int64 | 主键 |
| `title` / `content` | string | 标题 / 问题描述 |
| `category` | string | 分类编码，见 [字段字典](./README.md#分类category) |
| `categoryName` | string | 分类中文名，由编码翻译，不存在库里 |
| `priority` | int | 1 低 / 2 中 / 3 高 |
| `status` / `statusName` | int / string | 状态值与中文名 |
| `assigneeId` / `assigneeName` | int64 / string | 处理人。名称由框架按 ID 批量填充，不产生 N+1 查询 |
| `handleRemark` | string | 处理备注 |
| `aiReason` | string | AI 判定理由，模型输出的 `reason` 字段 |
| `aiFallback` | boolean | `true` 表示这次分类走了关键词规则，不是模型判的 |
| `aiCostMs` | int | 本次分类耗时（毫秒），调用模型失败时也会记录 |
| `createUserString` / `updateUserString` | string | 创建人 / 修改人昵称 |
| `disabled` | boolean | 是否禁止修改（框架内置字段） |

> 数据库里存了 `create_user` / `update_user` 这样的裸 ID，但响应里不返回，只返回翻译后的 `xxxString`。这是框架的约定，避免前端再发一次请求查昵称。

---

## 3. 查询详情

```
GET /ticket/manage/{id}
```

| 参数 | 位置 | 类型 | 必填 |
| --- | --- | --- | --- |
| `id` | path | int64 | 是 |

```bash
curl 'http://134.175.137.18/api/ticket/manage/4' -H 'Authorization: Bearer <accessToken>'
```

响应 `data` 是单个工单对象，字段与分页列表的元素完全一致。查不到时 `code` 为 `"1"`，`msg` 提示数据不存在。

---

## 4. 修改工单

```
PUT /ticket/manage/{id}
```

请求体与创建接口相同（`title`、`content` 仍为必填，等于全量覆盖）。**修改不会重新触发 AI 分类** —— 这是有意的：人工改过的分类不该被模型再覆盖一遍。

```bash
curl -X PUT 'http://134.175.137.18/api/ticket/manage/4' \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{
    "title": "登不进去",
    "content": "输入账号之后一直停在加载页面，同事也遇到同样的情况，整个组都用不了",
    "category": "fault",
    "priority": 3,
    "status": 2,
    "assigneeId": 2,
    "handleRemark": "已定位为网关超时，正在扩容"
  }'
```

响应 `data` 为 `null`。

**人工纠正的使用方式**：模型判错时直接用这个接口覆盖 `category` / `priority`，`aiReason` 保留原判定理由，`aiFallback` 不变。保留原值是为了后续分析「模型在哪些措辞上容易错」。

---

## 5. 删除单条

```
DELETE /ticket/manage/{id}
```

逻辑删除（`deleted` 字段置为记录 ID），数据仍在库里，可追溯。

```bash
curl -X DELETE 'http://134.175.137.18/api/ticket/manage/4' -H 'Authorization: Bearer <accessToken>'
```

响应 `data` 为 `null`。

---

## 6. 批量删除

```
DELETE /ticket/manage
```

注意是 **DELETE 带请求体**，参数放在 body 里而不是查询串：

```json
{ "ids": [4, 5, 6] }
```

```bash
curl -X DELETE 'http://134.175.137.18/api/ticket/manage' \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Content-Type: application/json' \
  -d '{ "ids": [4, 5, 6] }'
```

`ids` 不能为空，至少一个元素。

---

## 7. 重新分类

```
POST /ticket/manage/{id}/classify
```

对指定工单重新跑一次分类判定，用于：模型当时不可用走了兜底、人工觉得判得不对、提示词调整后需要重跑历史数据。

与创建时的异步分类不同，**这个接口是同步的** —— 返回时分类结果已经写回数据库，紧接着查详情就能看到新结果。

```bash
curl -X POST 'http://134.175.137.18/api/ticket/manage/4/classify' \
  -H 'Authorization: Bearer <accessToken>'
```

响应 `data` 为 `null`。模型不可用时不会报错，会静默走关键词规则并把 `aiFallback` 置为 `true`。

---

## 8. 统计指标

```
GET /ticket/manage/statistics
```

**响应**

```json
{
  "code": "0",
  "msg": "ok",
  "success": true,
  "timestamp": 1793602501000,
  "data": {
    "total": 4,
    "pendingCount": 4,
    "categoryCount": {
      "account": 1,
      "data": 2,
      "feature": 1
    },
    "priorityCount": {
      "1": 1,
      "2": 2,
      "3": 1
    },
    "fallbackCount": 0,
    "fallbackRate": 0.0,
    "avgCostMs": 1597.75
  }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `total` | int64 | 工单总数 |
| `pendingCount` | int64 | 待处理数量 |
| `categoryCount` | object | 各分类数量，key 是分类编码 |
| `priorityCount` | object | 各优先级数量，key 是 `1`/`2`/`3` |
| `fallbackCount` | int64 | 走规则兜底的工单数 |
| `fallbackRate` | double | 兜底占比，0～1 |
| `avgCostMs` | double | 模型平均耗时（毫秒），无数据时为 `null` |

**这几个指标是判断 AI 是否真的在工作的依据**：

- `fallbackRate` 突然从 0 涨到 0.5，说明模型挂了或密钥失效，页面顶部会标红（阈值 30%）
- `avgCostMs` 持续超过 3 秒，说明该加缓存或换更快的模型
- `categoryCount` 里 `other` 占比过高，说明提示词里的分类定义没覆盖住真实业务

---

## 9. 导出 Excel

```
GET /ticket/manage/export
```

查询参数与分页接口相同（`keyword` / `category` / `priority` / `status` / `aiFallback` / `sort`），但没有 `page` / `size` —— 导出的是全部命中数据。

```bash
curl -L 'http://134.175.137.18/api/ticket/manage/export?status=1' \
  -H 'Authorization: Bearer <accessToken>' \
  -o tickets.xlsx
```

返回的是 `.xlsx` 文件流，不是 JSON 信封。列顺序由响应模型上的 `@ExcelProperty` 注解决定，中文表头。
