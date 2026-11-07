import type { TicketResp, TicketUpdateReq } from '@/apis/ticket'

export interface TicketEditForm {
  title: string
  content: string
  status: number
  category?: string
  priority?: number
  handleRemark: string
}

export const formatCost = (value?: number | null) => {
  if (value == null) return '—'
  if (value < 1000) return `${Math.round(value)} ms`
  const seconds = Math.round(value / 1000)
  if (seconds < 60) return `${(value / 1000).toFixed(1)} 秒`
  return `${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒`
}

export const validateTicketText = (title: string, content: string) => {
  if (!title.trim() || !content.trim()) return '标题和问题描述都要填'
  if (title.trim().length > 100 || content.trim().length > 2000) {
    return '标题最多 100 字，问题描述最多 2000 字'
  }
  return null
}

export const validateTicketEdit = (form: TicketEditForm) => {
  const textError = validateTicketText(form.title, form.content)
  if (textError) return textError
  if (form.handleRemark.length > 500) return '处理备注最多 500 字'
  if (![1, 2, 3, 4].includes(form.status)) return '请选择工单状态'
  if (!!form.category !== !!form.priority) return '人工分类和优先级须同时填写'
  return null
}

export const buildTicketUpdate = (current: TicketResp, form: TicketEditForm): TicketUpdateReq => {
  const classificationChanged = form.category !== current.category || form.priority !== current.priority
  return {
    revision: current.revision,
    title: form.title.trim(),
    content: form.content.trim(),
    status: form.status,
    handleRemark: form.handleRemark.trim(),
    ...(classificationChanged && form.category && form.priority
      ? { category: form.category, priority: form.priority }
      : {}),
  }
}
