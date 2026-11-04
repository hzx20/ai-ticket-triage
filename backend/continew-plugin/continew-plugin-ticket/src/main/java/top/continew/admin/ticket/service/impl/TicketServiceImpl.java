/*
 * Copyright (c) 2022-present Charles7c Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package top.continew.admin.ticket.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.continew.admin.common.base.service.BaseServiceImpl;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.enums.TicketStatusEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.model.query.TicketQuery;
import top.continew.admin.ticket.model.req.TicketReq;
import top.continew.admin.ticket.model.resp.TicketResp;
import top.continew.admin.ticket.model.resp.TicketStatisticsResp;
import top.continew.admin.ticket.service.TicketService;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工单业务实现
 *
 * @author hzx20
 * @since 1.0.0
 */
@Service
@RequiredArgsConstructor
public class TicketServiceImpl
    extends BaseServiceImpl<TicketMapper, TicketDO, TicketResp, TicketResp, TicketQuery, TicketReq>
    implements TicketService {

    private final TicketClassifyTask ticketClassifyTask;

    private final ClassifyCache classifyCache;

    /**
     * 创建工单
     *
     * <p>
     * 关键设计：先把工单落库并立刻返回 ID，再异步去做 AI 分类。
     * 这样用户提交工单不会被模型响应速度拖住——模型慢的时候提交接口依然是毫秒级返回。
     * 代价是刚提交的那一瞬间分类字段是空的，前端需要刷新一次才能看到结果。
     * </p>
     *
     * <p>
     * 注意这里必须用事务同步回调提交异步任务：create 方法带 {@code @Transactional}，
     * 如果直接 submit，异步线程可能在事务提交前就去查库，查不到记录（实测确实出现过
     * 「工单不存在，跳过分类」）。注册 afterCommit 回调，保证任务一定在数据可见之后才执行。
     * </p>
     */
    @Override
    public Long create(TicketReq req) {
        if (req.getStatus() == null) {
            req.setStatus(TicketStatusEnum.PENDING.getValue());
        }
        Long id = super.create(req);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager
                .registerSynchronization(new TransactionSynchronization() {

                    @Override
                    public void afterCommit() {
                        ticketClassifyTask.submit(id);
                    }
                });
        } else {
            ticketClassifyTask.submit(id);
        }
        return id;
    }

    /**
     * 人工触发重新分类（同步，接口返回时分类结果已经写回）
     */
    @Override
    public void classify(Long id) {
        ticketClassifyTask.doClassify(id);
    }

    @Override
    public TicketStatisticsResp statistics() {
        long total = this.count();
        long pending =
            this.lambdaQuery().eq(TicketDO::getStatus, TicketStatusEnum.PENDING.getValue()).count();
        long fallback = this.lambdaQuery().eq(TicketDO::getAiFallback, true).count();

        Map<String, Long> categoryCount = new LinkedHashMap<>();
        List<Map<String, Object>> categoryRows = baseMapper.selectMaps(new QueryWrapper<TicketDO>()
            .select("category AS k", "COUNT(*) AS cnt")
            .isNotNull("category")
            .groupBy("category"));
        for (Map<String, Object> row : categoryRows) {
            categoryCount.put(String.valueOf(row.get("k")), toLong(row.get("cnt")));
        }

        Map<Integer, Long> priorityCount = new LinkedHashMap<>();
        List<Map<String, Object>> priorityRows = baseMapper.selectMaps(new QueryWrapper<TicketDO>()
            .select("priority AS k", "COUNT(*) AS cnt")
            .isNotNull("priority")
            .groupBy("priority"));
        for (Map<String, Object> row : priorityRows) {
            priorityCount.put(Integer.valueOf(String.valueOf(row.get("k"))),
                toLong(row.get("cnt")));
        }

        Double avgCost = null;
        List<Map<String, Object>> avgRows = baseMapper.selectMaps(new QueryWrapper<TicketDO>()
            .select("AVG(ai_cost_ms) AS avg_cost"));
        if (!avgRows.isEmpty() && avgRows.get(0).get("avg_cost") != null) {
            avgCost = new BigDecimal(String.valueOf(avgRows.get(0).get("avg_cost"))).doubleValue();
        }

        // 按结果来源分组：数量 + 平均耗时。
        // 缓存到底省了多少时间，只有把「模型平均耗时」和「缓存平均耗时」并排放才说得清
        Map<String, Long> sourceCount = new LinkedHashMap<>();
        Double modelAvgCost = null;
        Double cacheAvgCost = null;
        List<Map<String, Object>> sourceRows = baseMapper.selectMaps(new QueryWrapper<TicketDO>()
            .select("ai_source AS k", "COUNT(*) AS cnt", "AVG(ai_cost_ms) AS avg_cost")
            .isNotNull("ai_source")
            .groupBy("ai_source"));
        for (Map<String, Object> row : sourceRows) {
            String source = String.valueOf(row.get("k"));
            sourceCount.put(source, toLong(row.get("cnt")));
            Object avg = row.get("avg_cost");
            if (avg == null) {
                continue;
            }
            double value = new BigDecimal(String.valueOf(avg)).doubleValue();
            if ("MODEL".equals(source)) {
                modelAvgCost = value;
            } else if ("CACHE".equals(source)) {
                cacheAvgCost = value;
            }
        }
        double savedCost = modelAvgCost != null && cacheAvgCost != null
            ? Math.max(modelAvgCost - cacheAvgCost, 0D)
            : 0D;

        return TicketStatisticsResp.builder()
            .total(total)
            .pendingCount(pending)
            .categoryCount(categoryCount)
            .priorityCount(priorityCount)
            .fallbackCount(fallback)
            .fallbackRate(total == 0 ? 0D : (double) fallback / total)
            .avgCostMs(avgCost)
            .sourceCount(sourceCount)
            .modelAvgCostMs(modelAvgCost)
            .cacheAvgCostMs(cacheAvgCost)
            .savedCostMs(savedCost)
            .cacheHitCount(classifyCache.stats().hit())
            .cacheMissCount(classifyCache.stats().miss())
            .cacheHitRate(classifyCache.hitRate())
            .cacheLockMissCount(classifyCache.stats().lockMiss())
            .cachePollHitCount(classifyCache.stats().pollHit())
            .cacheErrorCount(classifyCache.stats().error())
            .build();
    }

    private long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return Long.parseLong(String.valueOf(value));
    }
}
