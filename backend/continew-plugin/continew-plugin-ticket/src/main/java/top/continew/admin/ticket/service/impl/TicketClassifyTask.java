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

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;

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

    private final TicketMapper ticketMapper;

    private final TicketClassifier ticketClassifier;

    private final ClassifyCache classifyCache;

    private final ThreadPoolTaskExecutor executor;

    public TicketClassifyTask(TicketMapper ticketMapper,
        TicketClassifier ticketClassifier,
        ClassifyCache classifyCache) {
        this.ticketMapper = ticketMapper;
        this.ticketClassifier = ticketClassifier;
        this.classifyCache = classifyCache;
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
        executor.execute(() -> this.doClassify(id));
    }

    /**
     * 同步执行分类（创建时异步调用、人工重新分类时同步调用，共用同一份逻辑）
     *
     * @param id 工单 ID
     */
    public void doClassify(Long id) {
        TicketDO ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            log.warn("工单不存在，跳过分类。id={}", id);
            return;
        }
        try {
            ClassifyResult result = classifyWithCache(ticket);
            writeBack(id, result);
            log.info("工单分类完成。id={}, category={}, priority={}, fallback={}, source={}, cost={}ms",
                id,
                result.category().getValue(), result.priority().getValue(), result.fallback(),
                result
                    .source().name(),
                result.costMs());
        } catch (Exception e) {
            // 分类环节任何异常都不能往外抛，否则线程池里抛出的异常没人接，日志也只有一行堆栈
            log.error("工单分类异常，id={}", id, e);
        }
    }

    /**
     * 带缓存的分类
     *
     * <p>
     * 流程是：查缓存 → 命中就直接用 → 未命中就抢计算权 → 调模型 → 只有可信结果才回填。
     * 抢不到计算权时不做任何等待，直接自己调模型：等待会把线程占住，而线程池只有 4 个线程，
     * 多调一次模型最坏多花 0.001 元，占住线程可能让整个分类链路排队。
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

        classifyCache.tryLock(cacheKey);

        ClassifyResult result = ticketClassifier.classify(ticket.getTitle(), ticket.getContent());

        // 兜底结果不写缓存：模型可能是临时故障，缓存兜底等于让接下来 5 分钟的同内容工单都用错误分类
        if (!result.fallback()) {
            classifyCache.put(cacheKey, result);
        }
        return result;
    }

    /**
     * 把分类结果写回工单
     *
     * @param id     工单 ID
     * @param result 分类结果
     */
    private void writeBack(Long id, ClassifyResult result) {
        TicketDO update = new TicketDO();
        update.setId(id);
        update.setCategory(result.category().getValue());
        update.setPriority(result.priority().getValue());
        update.setAiReason(result.reason());
        update.setAiFallback(result.fallback());
        update.setAiCostMs(result.costMs());
        // 记下这条结果是怎么来的，统计接口据此分组算「模型平均耗时」和「缓存平均耗时」
        update.setAiSource(result.source().name());
        ticketMapper.updateById(update);
    }

    /**
     * 应用关闭时优雅停掉线程池，避免正在分类的任务被硬砍
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
