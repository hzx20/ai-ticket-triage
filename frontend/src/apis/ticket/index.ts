import type * as T from './type'
import http from '@/utils/http'

export type * from './type'

// 注意：后端 @CrudRequestMapping 要求路径至少两段（/模块/资源），
// 权限前缀由路径推导为 ticket:manage，前端所有请求都要带这一段
const BASE_URL = '/ticket/manage'

/** @desc 分页查询工单 */
export function pageTicket(query?: T.TicketQuery) {
  return http.get<PageRes<T.TicketResp[]>>(BASE_URL, query)
}

/** @desc 查询工单详情 */
export function getTicket(id: string) {
  return http.get<T.TicketResp>(`${BASE_URL}/${id}`)
}

/** @desc 创建工单（后端会异步触发 AI 分类） */
export function createTicket(data: T.TicketReq) {
  return http.post(`${BASE_URL}`, data)
}

/** @desc 修改工单 */
export function updateTicket(id: string, data: T.TicketUpdateReq) {
  return http.put(`${BASE_URL}/${id}`, data)
}

/** @desc 删除工单 */
export function deleteTicket(id: string) {
  return http.del(`${BASE_URL}/${id}`)
}

/** @desc 对工单重新分类（同步，返回时结果已写回） */
export function classifyTicket(id: string) {
  return http.post(`${BASE_URL}/${id}/classify`)
}

/** @desc 查询当前可见工单的分类任务状态（最多 100 条） */
export function getClassificationStatuses(ids: string[]) {
  return http.get<Record<string, string>>(`${BASE_URL}/classification-status`, { ids: ids.join(',') })
}

/** @desc 工单统计 */
export function getTicketStatistics() {
  return http.get<T.TicketStatisticsResp>(`${BASE_URL}/statistics`)
}
