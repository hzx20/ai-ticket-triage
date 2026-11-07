/* eslint-disable test/no-import-node-test -- This project has no third-party test runner. */
import assert from 'node:assert/strict'
import test from 'node:test'
import { buildTicketUpdate, formatCost, validateTicketEdit, validateTicketText } from './ticket-ui.ts'

test('创建表单按服务端字数限制校验', () => {
  assert.equal(validateTicketText('  ', '内容'), '标题和问题描述都要填')
  assert.equal(validateTicketText('x'.repeat(101), '内容'), '标题最多 100 字，问题描述最多 2000 字')
  assert.equal(validateTicketText('标题', 'x'.repeat(2001)), '标题最多 100 字，问题描述最多 2000 字')
  assert.equal(validateTicketText('  标题  ', '  内容  '), null)
})

test('修改表单要求人工分类和优先级成对填写', () => {
  const form = { title: '标题', content: '内容', status: 2, category: 'fault', handleRemark: '' }
  assert.equal(validateTicketEdit(form), '人工分类和优先级须同时填写')
  assert.equal(validateTicketEdit({ ...form, priority: 3 }), null)
})

test('修改工单保留版本，文本变化时不提交旧的 AI 分类', () => {
  const current = { id: '1', revision: 7, title: '旧标题', content: '旧内容', status: 1, category: 'account', priority: 2 }
  const form = { title: ' 新标题 ', content: ' 新内容 ', status: 2, category: 'account', priority: 2, handleRemark: ' 已处理 ' }
  assert.deepEqual(buildTicketUpdate(current, form), {
    revision: 7,
    title: '新标题',
    content: '新内容',
    status: 2,
    handleRemark: '已处理',
  })
  assert.deepEqual(buildTicketUpdate(current, { ...form, category: 'fault', priority: 3 }), {
    revision: 7,
    title: '新标题',
    content: '新内容',
    status: 2,
    handleRemark: '已处理',
    category: 'fault',
    priority: 3,
  })
})

test('耗时显示保留单位且分钟不出现 60 秒', () => {
  assert.equal(formatCost(null), '—')
  assert.equal(formatCost(950), '950 ms')
  assert.equal(formatCost(1730), '1.7 秒')
  assert.equal(formatCost(244569), '4 分 5 秒')
  assert.equal(formatCost(59999), '1 分 0 秒')
  assert.equal(formatCost(60000), '1 分 0 秒')
})
