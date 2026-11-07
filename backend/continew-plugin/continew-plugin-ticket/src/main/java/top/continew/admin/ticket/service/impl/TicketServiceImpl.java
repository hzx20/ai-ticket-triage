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

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import top.continew.admin.common.base.service.BaseServiceImpl;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketStatusEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.model.query.TicketQuery;
import top.continew.admin.ticket.model.req.TicketReq;
import top.continew.admin.ticket.model.resp.TicketResp;
import top.continew.admin.ticket.model.resp.TicketStatisticsResp;
import top.continew.admin.ticket.service.TicketService;
import top.continew.starter.core.exception.BusinessException;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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

    private final TicketClassifyJobRepository classifyJobs;

    /**
     * 创建工单
     *
     * <p>
     * 工单与分类任务写在同一事务里；调度器扫描已提交的任务，异常退出后可恢复。
     * 刚提交时分类字段可能为空，前端通过刷新查询任务状态。
     * </p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(TicketReq req) {
        require(req.getRevision() == null, "创建工单不应提交版本号");
        validateStatus(req.getStatus());
        boolean autoClassify = requiresClassification(req);
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null && TenantContextHolder.isTenantDisabled()) {
            tenantId = 0L;
        }
        require(tenantId != null, "缺少租户上下文，无法创建工单");
        if (req.getStatus() == null) {
            req.setStatus(TicketStatusEnum.PENDING.getValue());
        }
        Long id = super.create(req);
        if (autoClassify) {
            classifyJobs.enqueue(tenantId, id, 0L);
        } else {
            int marked = baseMapper.update(null, new LambdaUpdateWrapper<TicketDO>()
                .eq(TicketDO::getId, id)
                .eq(TicketDO::getTenantId, tenantId)
                .set(TicketDO::getAiSource, "MANUAL"));
            require(marked == 1, "手工分类来源保存失败");
        }
        return id;
    }

    boolean requiresClassification(TicketReq req) {
        boolean hasCategory = req.getCategory() != null;
        boolean hasPriority = req.getPriority() != null;
        require(hasCategory == hasPriority, "手工分类和优先级必须同时提供");
        if (hasCategory) {
            boolean validCategory = Arrays.stream(TicketCategoryEnum.values())
                .anyMatch(category -> category.getValue().equals(req.getCategory()));
            require(validCategory && req.getPriority() >= 1 && req.getPriority() <= 3,
                "分类或优先级无效");
        }
        return !hasCategory;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(TicketReq req, Long id) {
        validateStatus(req.getStatus());
        beforeUpdate(req, id);
        require(req.getRevision() != null, "修改工单需要提供读取到的版本号");
        TicketDO existing = baseMapper.selectById(id);
        require(existing != null, "工单不存在或已删除");
        boolean classificationChanged = (req.getCategory() != null
            && !Objects.equals(req.getCategory(), existing.getCategory()))
            || (req.getPriority() != null
                && !Objects.equals(req.getPriority(), existing.getPriority()));
        boolean textChanged = !Objects.equals(req.getTitle(), existing.getTitle())
            || !Objects.equals(req.getContent(), existing.getContent());
        boolean invalidateAi = textChanged && !classificationChanged
            && !"MANUAL".equals(existing.getAiSource());
        String category = req.getCategory() == null ? existing.getCategory() : req.getCategory();
        Integer priority = req.getPriority() == null ? existing.getPriority() : req.getPriority();
        TicketReq classificationFields = new TicketReq();
        classificationFields.setCategory(invalidateAi ? null : category);
        classificationFields.setPriority(invalidateAi ? null : priority);
        boolean autoClassify = requiresClassification(classificationFields);
        TicketDO update = new TicketDO();
        BeanUtil.copyProperties(req, update, CopyOptions.create().ignoreNullValue());
        update.setRevision(null);
        LambdaUpdateWrapper<TicketDO> condition = new LambdaUpdateWrapper<TicketDO>()
            .eq(TicketDO::getId, id)
            .eq(TicketDO::getTenantId, existing.getTenantId())
            .eq(TicketDO::getRevision, req.getRevision())
            .setSql("revision = revision + 1");
        if (classificationChanged) {
            condition.set(TicketDO::getAiSource, "MANUAL")
                .set(TicketDO::getAiReason, null)
                .set(TicketDO::getAiCostMs, null)
                .set(TicketDO::getAiFallback, false);
        } else if (invalidateAi) {
            update.setCategory(null);
            update.setPriority(null);
            condition.set(TicketDO::getCategory, null)
                .set(TicketDO::getPriority, null)
                .set(TicketDO::getAiSource, null)
                .set(TicketDO::getAiReason, null)
                .set(TicketDO::getAiCostMs, null)
                .set(TicketDO::getAiFallback, false);
        }
        int changed = baseMapper.update(update, condition);
        require(changed == 1, "工单已被其他操作修改，请刷新后重试");
        BeanUtil.copyProperties(req, existing, CopyOptions.create().ignoreNullValue());
        existing.setRevision(req.getRevision() + 1);
        if (autoClassify) {
            require(existing.getTenantId() != null, "缺少工单租户，无法创建分类任务");
            classifyJobs.enqueue(existing.getTenantId(), id, existing.getRevision());
        }
        afterUpdate(req, existing);
    }

    private static void validateStatus(Integer status) {
        require(status == null || Arrays.stream(TicketStatusEnum.values())
            .anyMatch(value -> value.getValue().equals(status)), "工单状态无效");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BusinessException(message);
        }
    }

    /**
     * 人工触发重新分类（同步，接口返回时分类结果已经写回）
     */
    @Override
    public void classify(Long id) {
        ticketClassifyTask.classifyManually(id);
    }

    @Override
    public Map<Long, String> classificationStatuses(List<Long> ids) {
        require(ids != null && ids.size() <= 100, "最多查询 100 个工单的分类状态");
        if (ids.isEmpty()) {
            return Map.of();
        }
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null && TenantContextHolder.isTenantDisabled()) {
            tenantId = 0L;
        }
        require(tenantId != null, "缺少租户上下文");
        List<TicketDO> visible = baseMapper.selectList(new LambdaQueryWrapper<TicketDO>()
            .eq(TicketDO::getTenantId, tenantId)
            .in(TicketDO::getId, ids));
        Map<Long, Long> revisions = new LinkedHashMap<>();
        for (TicketDO ticket : visible) {
            revisions.put(ticket.getId(), ticket.getRevision());
        }
        return classifyJobs.statuses(tenantId, revisions);
    }

    @Override
    public TicketStatisticsResp statistics() {
        long total = this.count();
        long pending = baseMapper.selectCount(new LambdaQueryWrapper<TicketDO>()
            .eq(TicketDO::getStatus, TicketStatusEnum.PENDING.getValue()));
        // 尚未完成分类的工单不应稀释兜底率；aiCostMs 兼容引入 aiSource 前的历史记录。
        long classified = baseMapper.selectCount(new LambdaQueryWrapper<TicketDO>()
            .isNotNull(TicketDO::getAiCostMs));
        long fallback = baseMapper.selectCount(new LambdaQueryWrapper<TicketDO>()
            .isNotNull(TicketDO::getAiCostMs).eq(TicketDO::getAiFallback, true));

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
            .fallbackRate(classified == 0 ? 0D : (double) fallback / classified)
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
