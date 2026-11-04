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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.ai.CachedClassify;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.ClassifySource;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;

import java.time.Duration;

/**
 * 抢锁失败后的等待与保底行为测试
 *
 * <p>
 * 这里验证的是 {@link TicketClassifyTask} 里那段「抢锁 → 抢不到就轮询 → 等不到再自己算」的决策逻辑，
 * 而不是 Redis 本身的行为（那部分归 ClassifyCacheRedisTest 管）。所以 Redis 客户端用 Mockito 打桩，
 * 不起容器、不连真 Redis，构建环境里没有 Redis 也能跑。
 * </p>
 *
 * <p>
 * 为什么要在任务层测：真正的 bug 就在这一层——{@code tryLock} 的返回值没接住，导致「抢到锁」和
 * 「抢不到锁」两条分支行为完全一样。缓存类自己的单测全绿，也发现不了这个问题。
 * </p>
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyTaskCacheTest {

    private static final String TITLE = "登不进去";

    private static final String CONTENT = "整个组卡在加载页，密码输对了也进不去";

    private static final long TENANT_ID = 1L;

    private static final long TICKET_ID = 100L;

    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    private TicketMapper ticketMapper;

    private TicketClassifier ticketClassifier;

    private ClassifyCache classifyCache;

    private String cacheKey;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        ticketMapper = mock(TicketMapper.class);
        ticketClassifier = mock(TicketClassifier.class);
        classifyCache = newCache(redisTemplate);
        cacheKey = ClassifyCache.buildKey(TENANT_ID, TITLE, CONTENT);

        TicketDO ticket = new TicketDO();
        ticket.setId(TICKET_ID);
        ticket.setTenantId(TENANT_ID);
        ticket.setTitle(TITLE);
        ticket.setContent(CONTENT);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket);
    }

    @Test
    @DisplayName("抢锁失败时靠轮询拿到结果，不再重复调用模型")
    void lockMissedShouldPollAndReuseResult() throws Exception {
        // 抢锁失败：说明有别的线程正在算同一条工单
        when(valueOperations.setIfAbsent(eq(cacheKey), anyString(), any(Duration.class)))
            .thenReturn(false);
        // 第 1 次是入口处的读缓存（空）；后两次是轮询重读，第 2 次仍为空，第 3 次读到别人写好的结果
        when(valueOperations.get(cacheKey)).thenReturn(null, null, cachedJson());

        newTask(classifyCache).doClassify(TICKET_ID);

        // 关键断言：这条路径上模型一次都不该被调用，否则 SET NX 等于白写
        verify(ticketClassifier, never()).classify(anyString(), anyString());

        ArgumentCaptor<TicketDO> captor = ArgumentCaptor.forClass(TicketDO.class);
        verify(ticketMapper).updateById(captor.capture());
        assertThat(captor.getValue().getAiSource()).isEqualTo(ClassifySource.CACHE.name());
        assertThat(captor.getValue().getCategory()).isEqualTo(TicketCategoryEnum.ACCOUNT.getValue());

        ClassifyCache.Stats stats = classifyCache.stats();
        assertThat(stats.hit()).isEqualTo(1L);
        assertThat(stats.pollHit()).isEqualTo(1L);
        // 轮询期间的两次空读不算未命中，只有入口那次算
        assertThat(stats.miss()).isEqualTo(1L);
    }

    @Test
    @DisplayName("轮询超时后回退到自己调模型，保证一定有结果")
    void pollTimeoutShouldFallbackToModelCall() {
        when(valueOperations.setIfAbsent(eq(cacheKey), anyString(), any(Duration.class)))
            .thenReturn(false);
        // 一直是空：拿锁的线程没能在 150 毫秒内写出结果（或者它自己也失败了）
        when(valueOperations.get(cacheKey)).thenReturn(null);
        when(ticketClassifier.classify(TITLE, CONTENT))
            .thenReturn(ClassifyResult
                .ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.MEDIUM, "接口报 500", 1200));

        long start = System.currentTimeMillis();
        newTask(classifyCache).doClassify(TICKET_ID);
        long elapsed = System.currentTimeMillis() - start;

        verify(ticketClassifier, times(1)).classify(TITLE, CONTENT);
        // 3 次读取之间有 2 个 50 毫秒间隔，等待预算的下限是 100 毫秒
        assertThat(elapsed).isGreaterThanOrEqualTo(90L);
        // 非兜底结果照旧回填缓存，这条逻辑不能因为加了轮询就变
        verify(valueOperations).set(eq(cacheKey), anyString(), any(Duration.class));
        assertThat(classifyCache.stats().miss()).isEqualTo(1L);
    }

    @Test
    @DisplayName("抢到锁但结果是兜底时主动删标记，避免 key 被占满 60 秒")
    void fallbackResultShouldReleaseProcessingMark() {
        when(valueOperations.setIfAbsent(eq(cacheKey), anyString(), any(Duration.class)))
            .thenReturn(true);
        when(ticketClassifier.classify(TITLE, CONTENT))
            .thenReturn(ClassifyResult
                .ofRule(TicketCategoryEnum.OTHER, TicketPriorityEnum.MEDIUM, "模型不可用", 3));

        newTask(classifyCache).doClassify(TICKET_ID);

        verify(redisTemplate, times(1)).delete(cacheKey);
        // 兜底结果不写缓存，这个约束不变
        verify(valueOperations, never()).set(eq(cacheKey), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("抢到锁且拿到模型结果时只回填缓存，不额外删标记")
    void modelResultShouldOverwriteMarkInsteadOfDeleting() {
        when(valueOperations.setIfAbsent(eq(cacheKey), anyString(), any(Duration.class)))
            .thenReturn(true);
        when(ticketClassifier.classify(TITLE, CONTENT))
            .thenReturn(ClassifyResult
                .ofModel(TicketCategoryEnum.ACCOUNT, TicketPriorityEnum.HIGH, "多人无法登录", 1800));

        newTask(classifyCache).doClassify(TICKET_ID);

        verify(valueOperations).set(eq(cacheKey), anyString(), any(Duration.class));
        // put 用结果覆盖了标记，再 delete 一次是多余的一次网络往返
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    @DisplayName("Redis 完全不可用时直接调模型，不等待也不抛异常")
    void missingRedisShouldCallModelDirectly() {
        ClassifyCache noRedisCache = newCache(null);
        when(ticketClassifier.classify(TITLE, CONTENT))
            .thenReturn(ClassifyResult
                .ofModel(TicketCategoryEnum.DATA, TicketPriorityEnum.LOW, "导出格式错误", 900));

        newTask(noRedisCache).doClassify(TICKET_ID);

        verify(ticketClassifier, times(1)).classify(TITLE, CONTENT);
    }

    /**
     * 构造任务实例
     *
     * <p>
     * 线程池在构造器里创建，但只有 submit 才会真正起线程，测试直接调 doClassify，不会漏线程。
     * </p>
     *
     * @param cache 分类缓存
     * @return 任务实例
     */
    private TicketClassifyTask newTask(ClassifyCache cache) {
        return new TicketClassifyTask(ticketMapper, ticketClassifier, cache);
    }

    /**
     * 构造一个被 Mockito 接管的缓存实例
     *
     * @param template Redis 客户端，传 null 表示容器里没有这个 Bean
     * @return 分类缓存
     */
    private ClassifyCache newCache(StringRedisTemplate template) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        if (template != null) {
            beanFactory.addBean("stringRedisTemplate", template);
        }
        ClassifyCache cache = new ClassifyCache(beanFactory
            .getBeanProvider(StringRedisTemplate.class), new ObjectMapper());
        // 这些值平时由 @Value 注入，测试里没有容器，手工设置成和 application 默认值一致
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 300L);
        ReflectionTestUtils.setField(cache, "processingTtlSeconds", 60L);
        return cache;
    }

    /**
     * 造一份缓存的序列化值，格式和 ClassifyCache.put 写进去的完全一致
     *
     * @return JSON 字符串
     * @throws Exception 序列化失败
     */
    private String cachedJson() throws Exception {
        return new ObjectMapper().writeValueAsString(new CachedClassify(TicketCategoryEnum.ACCOUNT
            .getValue(), TicketPriorityEnum.HIGH.getValue(), "多人无法登录", false, 2162));
    }
}
