<template>
  <div class="overview-page">
    <!-- 顶部：这个页面是登录后第一眼看到的内容，所以先讲清楚项目是什么 -->
    <a-card class="hero" :bordered="false">
      <div class="hero-main">
        <h1>AI 工单分类系统</h1>
        <p>
          提交工单时不用自己选分类。大模型读一遍描述，判定分类与紧急程度，理由和耗时一并落库；
          模型不可用时自动降级为关键词规则，整条链路不会因为 AI 挂掉而停摆。
        </p>
        <div class="hero-tags">
          <a-tag color="arcoblue">Spring Boot 3.5</a-tag>
          <a-tag color="arcoblue">Vue 3</a-tag>
          <a-tag color="green">DeepSeek-V4.1-Flash</a-tag>
          <a-tag color="orange">Redis 短缓存</a-tag>
          <a-tag color="purple">Docker Compose</a-tag>
        </div>
      </div>
      <div class="hero-status">
        <span class="dot"></span>
        服务运行中
      </div>
    </a-card>

    <!-- 四个关键指标 -->
    <div class="stat-grid">
      <a-card :bordered="false">
        <a-statistic title="工单总数" :value="stat.total" />
      </a-card>
      <a-card :bordered="false">
        <a-statistic title="待处理" :value="stat.pendingCount" :value-style="{ color: '#ff7d00' }" />
      </a-card>
      <a-card :bordered="false">
        <a-statistic
          title="缓存命中率"
          :value="stat.cacheHitRate * 100"
          :precision="1"
          suffix="%"
          :value-style="{ color: '#165dff' }"
        />
      </a-card>
      <a-card :bordered="false">
        <a-statistic title="模型平均耗时" :value="stat.modelAvgCostMs || 0" :precision="0" suffix="ms" />
      </a-card>
    </div>

    <a-row :gutter="14">
      <!-- 最近工单 -->
      <a-col :xs="24" :lg="15">
        <a-card title="最近工单" :bordered="false" class="panel">
          <template #extra>
            <a class="more-link" @click="router.push('/ticket/list')">查看全部</a>
          </template>
          <div v-if="recent.length === 0" class="empty">
            {{ loadFailed ? '没有权限读取工单数据' : '暂无工单，去「工单管理」提交一条试试' }}
          </div>
          <div v-for="item in recent" :key="item.id" class="recent-item">
            <div class="recent-head">
              <span class="recent-title">{{ item.title }}</span>
              <a-tag :color="priorityColor(item.priority)">
                {{ priorityLabel(item.priority) }}
              </a-tag>
            </div>
            <div class="recent-meta">
              <a-tag size="small" :color="categoryColor(item.category)">
                {{ categoryLabel(item.category) }}
              </a-tag>
              <span v-if="item.aiFallback" class="fallback">规则兜底</span>
              <span class="muted">{{ item.aiCostMs ? `${item.aiCostMs} ms` : '待分类' }}</span>
              <span class="muted">{{ item.createTime }}</span>
            </div>
          </div>
        </a-card>
      </a-col>

      <!-- 右侧：分布 + 缓存效果 -->
      <a-col :xs="24" :lg="9">
        <a-card title="分类分布" :bordered="false" class="panel">
          <div v-for="row in distribution" :key="row.code" class="dist-row">
            <span class="dist-label">{{ row.label }}</span>
            <div class="dist-bar">
              <div class="dist-fill" :style="{ width: `${row.pct}%`, background: row.color }"></div>
            </div>
            <span class="dist-count">{{ row.count }}</span>
          </div>
        </a-card>

        <a-card title="缓存效果" :bordered="false" class="panel">
          <div class="cost-row">
            <span>调一次模型</span>
            <b>{{ round(stat.modelAvgCostMs) }} ms</b>
          </div>
          <div class="cost-row">
            <span>命中缓存</span>
            <b class="green">{{ round(stat.cacheAvgCostMs) }} ms</b>
          </div>
          <div class="cost-save">单次省下 {{ round(stat.savedCostMs) }} ms</div>
          <div class="cost-tip">命中率基于当前实例，重启归零；命中时不再产生模型调用费用</div>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getTicketStatistics, pageTicket } from '@/apis/ticket'
import type { TicketResp, TicketStatisticsResp } from '@/apis/ticket'

defineOptions({ name: 'TicketOverview' })

const router = useRouter()

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

const recent = ref<TicketResp[]>([])
const loadFailed = ref(false)

const categories = [
  { code: 'account', label: '账号与权限', color: '#ff7d00' },
  { code: 'fault', label: '系统故障', color: '#f53f3f' },
  { code: 'feature', label: '功能需求', color: '#165dff' },
  { code: 'data', label: '数据问题', color: '#722ed1' },
  { code: 'consult', label: '使用咨询', color: '#00b42a' },
  { code: 'other', label: '其他', color: '#86909c' },
]

const categoryLabel = (code?: string) => categories.find((c) => c.code === code)?.label || '待分类'
const categoryColor = (code?: string) => {
  const map: Record<string, string> = {
    account: 'orangered',
    fault: 'red',
    feature: 'arcoblue',
    data: 'purple',
    consult: 'green',
    other: 'gray',
  }
  return map[code || ''] || 'gray'
}
const priorityLabel = (priority?: number) => ({ 1: '低', 2: '中', 3: '高' })[priority || 0] || '—'
const priorityColor = (priority?: number) =>
  ({ 1: 'gray', 2: 'blue', 3: 'red' })[priority || 0] || 'gray'

const round = (value?: number | null) => Math.round(value || 0)

// 分类分布：按总数算百分比，宽度直接用 CSS，不引入图表库
const distribution = computed(() => {
  const counts = stat.value.categoryCount || {}
  const total = Object.values(counts).reduce((sum, n) => sum + n, 0) || 1
  return categories.map((c) => {
    const count = counts[c.code] || 0
    return { ...c, count, pct: Math.round((count / total) * 100) }
  })
})

onMounted(async () => {
  try {
    const [statRes, pageRes] = await Promise.all([
      getTicketStatistics(),
      pageTicket({ page: 1, size: 5 }),
    ])
    stat.value = statRes.data
    recent.value = pageRes.data.list || []
  } catch {
    // 演示账号可能没有统计权限，静默降级成空态，不弹错误打断观感
    loadFailed.value = true
  }
})
</script>

<style scoped lang="scss">
  .overview-page {
    padding: $margin;
  }

  .hero {
    margin-bottom: 14px;

    :deep(.arco-card-body) {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 20px;
    }

    h1 {
      margin: 0 0 8px;
      font-size: 22px;
      font-weight: 600;
    }

    p {
      max-width: 780px;
      margin: 0 0 12px;
      color: var(--color-text-2);
      line-height: 1.7;
    }
  }

  .hero-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 6px;
  }

  .hero-status {
    display: flex;
    flex-shrink: 0;
    align-items: center;
    gap: 6px;
    color: var(--color-text-3);
    font-size: 13px;
  }

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background: #00b42a;
    box-shadow: 0 0 0 3px rgb(0 180 42 / 15%);
  }

  .stat-grid {
    display: grid;
    grid-template-columns: repeat(4, minmax(0, 1fr));
    gap: 14px;
    margin-bottom: 14px;
  }

  @media (max-width: 1200px) {
    .stat-grid {
      grid-template-columns: repeat(2, minmax(0, 1fr));
    }
  }

  .panel {
    margin-bottom: 14px;
  }

  .more-link {
    color: rgb(var(--primary-6));
    cursor: pointer;
  }

  .empty {
    padding: 32px 0;
    color: var(--color-text-3);
    text-align: center;
  }

  .recent-item {
    padding: 12px 0;
    border-bottom: 1px solid var(--color-border-1);

    &:last-child {
      border-bottom: none;
    }
  }

  .recent-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
  }

  .recent-title {
    overflow: hidden;
    font-weight: 500;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .recent-meta {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-top: 6px;
  }

  .fallback {
    color: #ff7d00;
    font-size: 12px;
  }

  .muted {
    color: var(--color-text-3);
    font-size: 12px;
  }

  .dist-row {
    display: flex;
    align-items: center;
    gap: 10px;
    margin-bottom: 10px;

    &:last-child {
      margin-bottom: 0;
    }
  }

  .dist-label {
    width: 76px;
    flex-shrink: 0;
    font-size: 13px;
  }

  .dist-bar {
    height: 8px;
    flex: 1;
    overflow: hidden;
    border-radius: 4px;
    background: var(--color-fill-2);
  }

  .dist-fill {
    height: 100%;
    border-radius: 4px;
    transition: width 0.4s ease;
  }

  .dist-count {
    width: 22px;
    color: var(--color-text-3);
    font-size: 12px;
    text-align: right;
  }

  .cost-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 8px 0;
    border-bottom: 1px dashed var(--color-border-1);

    b {
      font-size: 16px;
    }
  }

  .green {
    color: #00b42a;
  }

  .cost-save {
    margin-top: 10px;
    color: #00b42a;
    font-weight: 600;
  }

  .cost-tip {
    margin-top: 6px;
    color: var(--color-text-3);
    font-size: 12px;
    line-height: 1.6;
  }
</style>
