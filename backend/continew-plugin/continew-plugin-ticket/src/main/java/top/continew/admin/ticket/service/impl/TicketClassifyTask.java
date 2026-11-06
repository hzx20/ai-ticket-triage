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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.starter.extension.tenant.context.TenantContext;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 工单分类任务
 *
 * <p>
 * 这里没有用 {@code @Async}，也不是随手写的，是被两个现实问题逼出来的：
 * </p>
 * <ol>
 * <li>如果把线程池注册成 Spring Bean（返回类型是 Executor），Spring Boot 的
 * TaskExecutionAutoConfiguration 会因为「容器里已经有 Executor」而不再创建默认的
 * applicationTaskExecutor，而项目里已有代码依赖它，应用会直接启动失败；</li>
 * <li>{@code @Async} 依赖代理生效，同一个类里自己调自己的 {@code @Async} 方法时代理被绕过，
 * 实际还是同步执行，属于很隐蔽的坑。</li>
 * </ol>
 * <p>
 * 所以这里自己持有一个线程池实例，提交任务时直接用：既拿到了线程隔离，又不干扰容器里的其他线程池。
 * </p>
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

    private final ThreadPoolTaskExecutor executor;

    private final TransactionTemplate isolatedTransaction;

    public TicketClassifyTask(TicketMapper ticketMapper,
        TicketClassifier ticketClassifier,
        ClassifyCache classifyCache,
        PlatformTransactionManager transactionManager) {
        this.ticketMapper = ticketMapper;
        this.ticketClassifier = ticketClassifier;
        this.classifyCache = classifyCache;
        this.isolatedTransaction = new TransactionTemplate(transactionManager);
        this.isolatedTransaction
            .setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(200);
        pool.setKeepAliveSeconds(60);
        pool.setThreadNamePrefix("ticket-classify-");
        // 队列满时由提交线程自己跑，宁可慢一点也不能把分类任务丢掉
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
    }

    /**
     * 异步提交分类任务
     *
     * @param id 工单 ID
     */
    public void submit(Long id) {
        TenantContext tenant = copyTenantContext(TenantContextHolder.getContext());
        boolean ignore = TenantContextHolder.isIgnore();
        Thread submittingThread = Thread.currentThread();
        executor.execute(() -> {
            // CallerRunsPolicy 可能在请求线程执行：保存并恢复已有上下文，不能直接清空。
            TenantContext previous = TenantContextHolder.getContext();
            boolean previousIgnore = TenantContextHolder.isIgnore();
            try {
                TenantContextHolder.setContext(tenant);
                TenantContextHolder.setIgnore(ignore);
                boolean isolateDatabase = Thread.currentThread() == submittingThread
                    && TransactionSynchronizationManager.isActualTransactionActive();
                this.doClassify(id, isolateDatabase);
            } finally {
                TenantContextHolder.clear();
                if (Thread.currentThread() == submittingThread) {
                    if (previous != null) {
                        TenantContextHolder.setContext(previous);
                    }
                    TenantContextHolder.setIgnore(previousIgnore);
                }
            }
        });
    }

    private TenantContext copyTenantContext(TenantContext source) {
        if (source == null) {
            return null;
        }
        TenantContext copy = new TenantContext();
        copy.setTenantId(source.getTenantId());
        copy.setIsolationLevel(source.getIsolationLevel());
        copy.setDataSource(source.getDataSource());
        return copy;
    }

    /**
     * 同步执行分类（创建时异步调用、人工重新分类时同步调用，共用同一份逻辑）
     *
     * @param id 工单 ID
     */
    public void doClassify(Long id) {
        doClassify(id, false);
    }

    private void doClassify(Long id, boolean isolateDatabase) {
        try {
            TicketDO ticket = isolateDatabase
                ? isolatedTransaction.execute(status -> ticketMapper.selectById(id))
                : ticketMapper.selectById(id);
            if (ticket == null) {
                log.warn("工单不存在，跳过分类。id={}", id);
                return;
            }
            ClassifyResult result = classifyWithCache(ticket);
            boolean written = isolateDatabase
                ? Boolean.TRUE
                    .equals(isolatedTransaction.execute(status -> writeBack(ticket, result)))
                : writeBack(ticket, result);
            if (written) {
                log.info(
                    "工单分类完成。id={}, category={}, priority={}, fallback={}, source={}, cost={}ms",
                    id, result.category().getValue(), result.priority().getValue(),
                    result.fallback(),
                    result.source().name(), result.costMs());
            } else {
                log.info("工单已被修改或删除，跳过过期的分类结果。id={}", id);
            }
        } catch (Exception e) {
            // 分类环节任何异常都不能往外抛，否则线程池里抛出的异常没人接，日志也只有一行堆栈
            log.error("工单分类异常，id={}", id, e);
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
    private ClassifyResult classifyWithCache(TicketDO ticket) {
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
    private boolean writeBack(TicketDO ticket, ClassifyResult result) {
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
            .eq(TicketDO::getTitle, ticket.getTitle())
            .eq(TicketDO::getContent, ticket.getContent());
        if (ticket.getCategory() == null) {
            condition.isNull(TicketDO::getCategory);
        } else {
            condition.eq(TicketDO::getCategory, ticket.getCategory());
        }
        if (ticket.getPriority() == null) {
            condition.isNull(TicketDO::getPriority);
        } else {
            condition.eq(TicketDO::getPriority, ticket.getPriority());
        }
        if (ticket.getUpdateTime() == null) {
            condition.isNull(TicketDO::getUpdateTime);
        } else {
            condition.eq(TicketDO::getUpdateTime, ticket.getUpdateTime());
        }
        // 这是快照比较，不是版本号：同秒内改动后又改回原值仍可能无法检测。
        return ticketMapper.update(update, condition) == 1;
    }

    /**
     * 应用关闭时优雅停掉线程池，避免正在分类的任务被硬砍
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
