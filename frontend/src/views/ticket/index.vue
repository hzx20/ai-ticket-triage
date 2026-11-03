<template>
  <div class="ticket-page">
    <!-- 统计卡片：这几个数用来判断 AI 分类是否可信 -->
    <div class="stat-grid">
      <a-card class="stat-card">
        <a-statistic title="工单总数" :value="stat.total" />
      </a-card>
      <a-card class="stat-card">
        <a-statistic title="待处理" :value="stat.pendingCount" :value-style="{ color: '#ff7d00' }" />
      </a-card>
      <a-card class="stat-card">
        <a-statistic
          title="规则兜底率"
          :value="stat.fallbackRate * 100"
          :precision="1"
          suffix="%"
          :value-style="{ color: stat.fallbackRate > 0.3 ? '#f53f3f' : '#00b42a' }"
        />
      </a-card>
      <a-card class="stat-card">
        <!-- 命中率本身不代表好坏了，Redis 异常才是真的有问题，所以这里用异常次数决定颜色 -->
        <a-statistic
          title="缓存命中率"
          :value="stat.cacheHitRate * 100"
          :precision="1"
          suffix="%"
          :value-style="{ color: stat.cacheErrorCount > 0 ? '#f53f3f' : '#165dff' }"
        />
      </a-card>
      <a-card class="stat-card">
        <a-statistic title="模型平均耗时" :value="stat.modelAvgCostMs || 0" :precision="0" suffix="ms" />
      </a-card>
      <a-card class="stat-card">
        <!-- 和左边那张并排放，缓存到底省了多少时间才看得出来 -->
        <a-statistic
          title="缓存命中耗时"
          :value="stat.cacheAvgCostMs || 0"
          :precision="0"
          suffix="ms"
          :value-style="{ color: '#00b42a' }"
        />
        <div class="stat-note">比调模型快 {{ stat.savedCostMs.toFixed(0) }} ms</div>
      </a-card>
    </div>

    <!-- 搜索条件 -->
    <a-card class="search-card">
      <a-form :model="query" layout="inline">
        <a-form-item label="关键词">
          <a-input v-model="query.keyword" placeholder="标题或内容" allow-clear style="width: 180px" />
        </a-form-item>
        <a-form-item label="分类">
          <a-select v-model="query.category" placeholder="全部" allow-clear style="width: 130px">
            <a-option v-for="item in categoryOptions" :key="item.value" :value="item.value">
              {{ item.label }}
            </a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="优先级">
          <a-select v-model="query.priority" placeholder="全部" allow-clear style="width: 110px">
            <a-option :value="3">高</a-option>
            <a-option :value="2">中</a-option>
            <a-option :value="1">低</a-option>
          </a-select>
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model="query.status" placeholder="全部" allow-clear style="width: 110px">
            <a-option :value="1">待处理</a-option>
            <a-option :value="2">处理中</a-option>
            <a-option :value="3">已解决</a-option>
            <a-option :value="4">已关闭</a-option>
          </a-select>
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" @click="handleSearch">查询</a-button>
            <a-button @click="handleReset">重置</a-button>
            <a-button type="primary" status="success" @click="openCreate">新建工单</a-button>
          </a-space>
        </a-form-item>
      </a-form>
    </a-card>

    <!-- 列表 -->
    <a-card>
      <a-alert v-if="hasPendingClassify" type="info" class="tip">
        有工单正在等待 AI 分类，列表已自动刷新。分类是异步执行的，通常 1-3 秒完成。
      </a-alert>
      <a-table
        :data="list"
        :loading="loading"
        :pagination="pagination"
        :bordered="false"
        row-key="id"
        @page-change="handlePageChange"
      >
        <template #columns>
          <a-table-column title="标题" data-index="title" :width="220" ellipsis>
            <template #cell="{ record }">
              <a-link @click="openDetail(record)">{{ record.title }}</a-link>
            </template>
          </a-table-column>
          <a-table-column title="分类" :width="110">
            <template #cell="{ record }">
              <a-tag v-if="record.category" :color="categoryColor(record.category)">
                {{ record.categoryName }}
              </a-tag>
              <span v-else class="muted">分类中…</span>
            </template>
          </a-table-column>
          <a-table-column title="优先级" :width="90">
            <template #cell="{ record }">
              <a-tag v-if="record.priority" :color="priorityColor(record.priority)">
                {{ priorityLabel(record.priority) }}
              </a-tag>
              <span v-else class="muted">—</span>
            </template>
          </a-table-column>
          <a-table-column title="状态" :width="90">
            <template #cell="{ record }">
              <a-tag :color="statusColor(record.status)">{{ record.statusName }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="AI 判定" :width="200" ellipsis>
            <template #cell="{ record }">
              <a-tooltip :content="record.aiReason || '暂无'">
                <span>{{ record.aiReason || '—' }}</span>
              </a-tooltip>
            </template>
          </a-table-column>
          <a-table-column title="来源" :width="90">
            <template #cell="{ record }">
              <a-tag v-if="record.aiFallback" color="orange">规则兜底</a-tag>
              <a-tag v-else-if="record.category" color="arcoblue">模型</a-tag>
              <span v-else class="muted">—</span>
            </template>
          </a-table-column>
          <a-table-column title="耗时" data-index="aiCostMs" :width="80">
            <template #cell="{ record }">{{ record.aiCostMs ? `${record.aiCostMs}ms` : '—' }}</template>
          </a-table-column>
          <a-table-column title="创建时间" data-index="createTime" :width="160" />
          <a-table-column title="操作" :width="180" fixed="right">
            <template #cell="{ record }">
              <a-space>
                <a-button type="text" size="small" @click="handleClassify(record)">重新分类</a-button>
                <a-button type="text" size="small" status="danger" @click="handleDelete(record)">删除</a-button>
              </a-space>
            </template>
          </a-table-column>
        </template>
      </a-table>
    </a-card>

    <!-- 新建工单 -->
    <a-modal
      v-model:visible="createVisible"
      title="新建工单"
      :ok-loading="submitting"
      :on-before-ok="handleCreate"
      @cancel="resetForm"
    >
      <a-form :model="form" layout="vertical">
        <a-form-item label="标题" required>
          <a-input v-model="form.title" placeholder="一句话说明问题，例如：后台登录一直提示验证码错误" />
        </a-form-item>
        <a-form-item label="问题描述" required>
          <a-textarea
            v-model="form.content"
            placeholder="描述具体现象、出现时间、影响范围。内容越具体，AI 分类越准。"
            :auto-size="{ minRows: 5, maxRows: 10 }"
          />
        </a-form-item>
        <a-alert type="info">提交后立即入库返回，AI 分类在后台异步执行，稍后刷新列表即可看到结果。</a-alert>
      </a-form>
    </a-modal>

    <!-- 详情 -->
    <a-drawer v-model:visible="detailVisible" title="工单详情" :width="480">
      <a-descriptions v-if="detail" :column="1" bordered>
        <a-descriptions-item label="标题">{{ detail.title }}</a-descriptions-item>
        <a-descriptions-item label="内容">{{ detail.content }}</a-descriptions-item>
        <a-descriptions-item label="分类">{{ detail.categoryName || '待分类' }}</a-descriptions-item>
        <a-descriptions-item label="优先级">{{ detail.priority ? priorityLabel(detail.priority) : '—' }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ detail.statusName }}</a-descriptions-item>
        <a-descriptions-item label="AI 理由">{{ detail.aiReason || '—' }}</a-descriptions-item>
        <a-descriptions-item label="判定来源">
          {{ detail.aiFallback ? '关键词规则兜底' : detail.category ? '大模型' : '待分类' }}
        </a-descriptions-item>
        <a-descriptions-item label="耗时">{{ detail.aiCostMs ? `${detail.aiCostMs} ms` : '—' }}</a-descriptions-item>
        <a-descriptions-item label="创建时间">{{ detail.createTime }}</a-descriptions-item>
      </a-descriptions>
    </a-drawer>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import {
  classifyTicket,
  createTicket,
  deleteTicket,
  getTicket,
  getTicketStatistics,
  pageTicket,
} from '@/apis/ticket'
import type { TicketResp, TicketStatisticsResp } from '@/apis/ticket'

defineOptions({ name: 'TicketList' })

const loading = ref(false)
const submitting = ref(false)
const list = ref<TicketResp[]>([])
const total = ref(0)

const query = reactive({
  keyword: '',
  category: undefined as string | undefined,
  priority: undefined as number | undefined,
  status: undefined as number | undefined,
})

const pagination = reactive({ current: 1, pageSize: 10, total: 0, showTotal: true, showPageSize: true })

const categoryOptions = [
  { value: 'account', label: '账号与权限' },
  { value: 'fault', label: '系统故障' },
  { value: 'feature', label: '功能需求' },
  { value: 'data', label: '数据问题' },
  { value: 'consult', label: '使用咨询' },
  { value: 'other', label: '其他' },
]

// 统计默认值，避免首次渲染时 undefined 报警
const stat = ref<TicketStatisticsResp>({
  total: 0,
  pendingCount: 0,
  categoryCount: {},
  priorityCount: {},
  fallbackCount: 0,
  fallbackRate: 0,
  avgCostMs: null,
  cacheHitCount: 0,
  cacheMissCount: 0,
  cacheHitRate: 0,
  cacheLockMissCount: 0,
  cacheErrorCount: 0,
  sourceCount: {},
  modelAvgCostMs: null,
  cacheAvgCostMs: null,
  savedCostMs: 0,
})

// 还有工单没分类完时给出提示
const hasPendingClassify = computed(() => list.value.some((item) => !item.category))

const categoryColor = (category: string) => {
  const map: Record<string, string> = {
    account: 'orangered',
    fault: 'red',
    feature: 'arcoblue',
    data: 'purple',
    consult: 'green',
    other: 'gray',
  }
  return map[category] || 'gray'
}

const priorityLabel = (priority: number) => ({ 1: '低', 2: '中', 3: '高' })[priority] || '—'
const priorityColor = (priority: number) => ({ 1: 'gray', 2: 'blue', 3: 'red' })[priority] || 'gray'
const statusColor = (status: number) =>
  ({ 1: 'orange', 2: 'arcoblue', 3: 'green', 4: 'gray' })[status] || 'gray'

const fetchList = async () => {
  loading.value = true
  try {
    const { data } = await pageTicket({
      ...query,
      page: pagination.current,
      size: pagination.pageSize,
    })
    list.value = data?.list || []
    total.value = data?.total || 0
    pagination.total = total.value
  } finally {
    loading.value = false
  }
}

const fetchStat = async () => {
  const { data } = await getTicketStatistics()
  if (data) stat.value = data
}

const refresh = () => Promise.all([fetchList(), fetchStat()])

const handleSearch = () => {
  pagination.current = 1
  refresh()
}

const handleReset = () => {
  query.keyword = ''
  query.category = undefined
  query.priority = undefined
  query.status = undefined
  handleSearch()
}

const handlePageChange = (page: number) => {
  pagination.current = page
  fetchList()
}

// 新建工单
const createVisible = ref(false)
const form = reactive({ title: '', content: '' })

const openCreate = () => {
  form.title = ''
  form.content = ''
  createVisible.value = true
}

const resetForm = () => {
  form.title = ''
  form.content = ''
}

const handleCreate = async () => {
  if (!form.title.trim() || !form.content.trim()) {
    Message.warning('标题和问题描述都要填')
    return false
  }
  submitting.value = true
  try {
    await createTicket({ title: form.title.trim(), content: form.content.trim() })
    Message.success('已提交，AI 正在后台分类')
    createVisible.value = false
    resetForm()
    pagination.current = 1
    await refresh()
    // 分类是异步的，2 秒后再刷一次，通常这时结果已经回来了
    setTimeout(refresh, 2000)
    return true
  } catch {
    return false
  } finally {
    submitting.value = false
  }
}

// 重新分类
const handleClassify = async (record: TicketResp) => {
  Message.info('正在调用模型重新分类…')
  await classifyTicket(record.id)
  Message.success('分类已更新')
  refresh()
}

// 删除
const handleDelete = (record: TicketResp) => {
  Modal.warning({
    title: '确认删除',
    content: `确定删除工单「${record.title}」吗？`,
    hideCancel: false,
    onOk: async () => {
      await deleteTicket(record.id)
      Message.success('已删除')
      refresh()
    },
  })
}

// 详情
const detailVisible = ref(false)
const detail = ref<TicketResp | null>(null)

const openDetail = async (record: TicketResp) => {
  const { data } = await getTicket(record.id)
  detail.value = data || null
  detailVisible.value = true
}

onMounted(refresh)
</script>

<style scoped lang="scss">
  .ticket-page {
    padding: $margin;
  }

  .stat-grid {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 14px;
  }

  // 六张卡片用 24 栅格除不尽（6 张 × 4 = 24 太窄，×6 = 36 超了），所以用 grid 而不是 a-row
  @media (max-width: 1200px) {
    .stat-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  @media (max-width: 768px) {
    .stat-grid {
      grid-template-columns: minmax(0, 1fr);
    }
  }

  .stat-note {
    margin-top: 4px;
    color: var(--color-text-3);
    font-size: 12px;
  }

  .stat-card {
    height: 100%;
  }

  .search-card {
    margin-bottom: 14px;
  }

  .tip {
    margin-bottom: 12px;
  }

  .muted {
    color: var(--color-text-3);
  }
</style>
