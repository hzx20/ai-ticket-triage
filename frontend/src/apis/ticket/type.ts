/** 工单响应 */
export interface TicketResp {
  id: string
  /** 更新时提交读取到的版本，避免覆盖其他人的修改 */
  revision: number | string
  title: string
  content: string
  /** 分类编码：account / fault / feature / data / consult / other */
  category?: string
  categoryName?: string
  /** 优先级：1 低、2 中、3 高 */
  priority?: number
  /** 状态：1 待处理、2 处理中、3 已解决、4 已关闭 */
  status: number
  statusName?: string
  assigneeId?: string
  assigneeName?: string
  handleRemark?: string
  /** AI 判定理由 */
  aiReason?: string
  /** 是否走了关键词规则兜底 */
  aiFallback?: boolean
  /** 判定来源：模型、规则或缓存；历史记录可能为空 */
  aiSource?: 'MODEL' | 'RULE' | 'CACHE' | 'MANUAL' | null
  /** AI 调用耗时（毫秒） */
  aiCostMs?: number
  createUserString?: string
  createTime?: string
  updateTime?: string
}

/** 工单查询条件 */
export interface TicketQuery {
  keyword?: string
  category?: string
  priority?: number
  status?: number
  aiFallback?: boolean
  page?: number
  size?: number
}

/** 工单创建或修改参数 */
export interface TicketReq {
  title: string
  content: string
  category?: string
  priority?: number
  status?: number
  assigneeId?: string
  handleRemark?: string
}

/** 修改时必须携带读取工单时的版本 */
export type TicketUpdateReq = TicketReq & { revision: number | string }

/** 工单统计 */
export interface TicketStatisticsResp {
  total: number
  pendingCount: number
  categoryCount: Record<string, number>
  priorityCount: Record<string, number>
  fallbackCount: number
  fallbackRate: number
  avgCostMs: number | null
  /** 缓存命中次数（当前实例，重启归零） */
  cacheHitCount: number
  /** 缓存未命中次数（当前实例，重启归零） */
  cacheMissCount: number
  /** 缓存命中率（0-1） */
  cacheHitRate: number
  /** 抢占计算权失败次数，说明存在并发重复提交 */
  cacheLockMissCount: number
  /** 缓存操作异常次数，Redis 不可用时会持续增长 */
  cacheErrorCount: number
  /** 各来源数量（MODEL / RULE / CACHE） */
  sourceCount: Record<string, number>
  /** 模型判定平均耗时（毫秒） */
  modelAvgCostMs: number | null
  /** 缓存命中平均耗时（毫秒） */
  cacheAvgCostMs: number | null
  /** 缓存省下的平均耗时（毫秒） */
  savedCostMs: number
}
