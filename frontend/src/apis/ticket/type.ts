/** 工单响应 */
export interface TicketResp {
  id: string
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

/** 工单统计 */
export interface TicketStatisticsResp {
  total: number
  pendingCount: number
  categoryCount: Record<string, number>
  priorityCount: Record<string, number>
  fallbackCount: number
  fallbackRate: number
  avgCostMs: number | null
}
