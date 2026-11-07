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

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.starter.core.exception.BusinessException;

import java.util.Optional;

/**
 * 工单分类任务
 *
 * 持久任务调度和手动重分类共用的分类及版本条件写回逻辑。
 *
 * @author hzx20
 * @since 1.0.0
 */
@Slf4j
@Component
public class TicketClassifyTask {

    /**
     * 抢锁失败后最多重读缓存几次
     */
    private static final int POLL_MAX_ATTEMPTS = 3;

    /**
     * 两次重读之间的间隔（毫秒）
     */
    private static final long POLL_INTERVAL_MILLIS = 50L;

    private final TicketMapper ticketMapper;

    private final TicketClassifier ticketClassifier;

    private final ClassifyCache classifyCache;

    public TicketClassifyTask(TicketMapper ticketMapper,
        TicketClassifier ticketClassifier,
        ClassifyCache classifyCache) {
        this.ticketMapper = ticketMapper;
        this.ticketClassifier = ticketClassifier;
        this.classifyCache = classifyCache;
    }

    /**
     * 同步重新分类：失败或版本冲突须明确反馈调用方。
     *
     * @param id 工单 ID
     */
    public void classifyManually(Long id) {
        TicketDO snapshot = ticketMapper.selectById(id);
        if (snapshot == null) {
            throw new BusinessException("工单不存在或已删除");
        }
        ClassifyResult result =
            ticketClassifier.classify(snapshot.getTitle(), snapshot.getContent());
        if (!writeBack(snapshot, result)) {
            throw new BusinessException("工单已被其他操作修改，请刷新后重试");
        }
    }

    /**
     * 带缓存的分类
     *
     * <p>
     * 流程：查缓存 → 命中直接用 → 未命中抢计算权（SET NX）→ 抢到就自己调模型，抢不到先短轮询等结果
     * → 等不到才自己调模型 → 只有可信结果才回填。
     * </p>
     *
     * <p>
     * 最多重读 {@value #POLL_MAX_ATTEMPTS} 次，相邻两次间隔
     * {@value #POLL_INTERVAL_MILLIS} 毫秒。Redis 的实际往返耗时不包含在间隔中，
     * 因此总等待时间没有由这两个参数严格限定。等不到时仍会产生重复模型调用。
     * </p>
     *
     * @param ticket 工单
     * @return 分类结果
     */
    ClassifyResult classifyWithCache(TicketDO ticket) {
        String cacheKey = ClassifyCache.buildKey(ticket.getTenantId(), ticket.getTitle(), ticket
            .getContent());

        Optional<ClassifyResult> cached = classifyCache.get(cacheKey);
        if (cached.isPresent()) {
            log.info("分类缓存命中，跳过模型调用。id={}, key={}", ticket.getId(), cacheKey);
            return cached.get();
        }

        // 这个返回值是「我是计算者还是等待者」的唯一依据，必须接住
        Optional<String> marker = classifyCache.tryLock(cacheKey);
        if (marker.isEmpty()) {
            // 抢不到说明可能有别的线程正在算同一条工单，先等它，别急着重复调模型
            Optional<ClassifyResult> awaited = classifyCache
                .awaitResult(cacheKey, POLL_MAX_ATTEMPTS, POLL_INTERVAL_MILLIS);
            if (awaited.isPresent()) {
                log.info("轮询等到其他线程的分类结果，跳过模型调用。id={}, key={}", ticket
                    .getId(), cacheKey);
                return awaited.get();
            }
            log.info("轮询未等到结果，回退为本次直接调用模型。id={}, key={}", ticket.getId(), cacheKey);
        }

        try {
            ClassifyResult result =
                ticketClassifier.classify(ticket.getTitle(), ticket.getContent());
            // 等待者可能已自行计算，但只有持有有效标记的计算者可以回填缓存。
            marker.ifPresent(value -> classifyCache.putIfOwned(cacheKey, value, result));
            return result;
        } finally {
            // Lua 比较标记后才删除，避免超时后删除其他请求的标记或已写入的结果。
            marker.ifPresent(value -> classifyCache.releaseMark(cacheKey, value));
        }
    }

    /**
     * 把分类结果写回工单
     *
     * @param ticket 分类开始时读取的工单快照
     * @param result 分类结果
     * @return 是否写入成功
     */
    boolean writeBack(TicketDO ticket, ClassifyResult result) {
        TicketDO update = new TicketDO();
        update.setCategory(result.category().getValue());
        update.setPriority(result.priority().getValue());
        update.setAiReason(result.reason());
        update.setAiFallback(result.fallback());
        update.setAiCostMs(result.costMs());
        // 记下这条结果是怎么来的，统计接口据此分组算「模型平均耗时」和「缓存平均耗时」
        update.setAiSource(result.source().name());
        LambdaUpdateWrapper<TicketDO> condition = new LambdaUpdateWrapper<TicketDO>()
            .eq(TicketDO::getId, ticket.getId())
            .eq(TicketDO::getTenantId, ticket.getTenantId())
            .eq(TicketDO::getRevision, ticket.getRevision())
            .setSql("revision = revision + 1");
        return ticketMapper.update(update, condition) == 1;
    }

}
