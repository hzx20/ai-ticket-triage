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

package top.continew.admin.ticket.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;

import java.time.Duration;
import java.util.Optional;

/**
 * 轮询等待与标记释放的计数口径测试
 *
 * <p>
 * 这里只关心两件事：轮询期间的读写算进哪个计数器，以及 Redis 出问题时会不会把异常抛出去。
 * Redis 客户端用 Mockito 打桩，因此不依赖本地 Redis，任何环境都能跑。
 * </p>
 *
 * <p>
 * 计数口径看着琐碎，但它直接决定「缓存有没有用」这个结论可不可信：把轮询的空读算成未命中，
 * 命中率会被稀释；把轮询命中漏掉不计，又看不出等待到底救回了多少次模型调用。两种错法都会让
 * 统计接口给出相反的结论。
 * </p>
 *
 * @author hzx20
 * @since 1.1.0
 */
class ClassifyCachePollTest {

    private static final String KEY = ClassifyCache.buildKey(7L, "轮询测试", "验证计数口径");

    private StringRedisTemplate redisTemplate;

    private ValueOperations<String, String> valueOperations;

    private ClassifyCache classifyCache;

    @SuppressWarnings("unchecked")
    @DisplayName("轮询命中：算命中、单独计 pollHit，且不产生未命中")
    @Test
    void pollHitShouldCountAsHitOnly() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        classifyCache = newCache(redisTemplate);
        // 第 1 次读空，第 2 次读到结果
        when(valueOperations.get(KEY)).thenReturn(null, cachedJson());

        Optional<ClassifyResult> result = classifyCache.awaitResult(KEY, 3, 10L);

        assertThat(result).isPresent();
        assertThat(result.get().category()).isEqualTo(TicketCategoryEnum.ACCOUNT);
        assertThat(result.get().source()).isEqualTo(ClassifySource.CACHE);

        ClassifyCache.Stats stats = classifyCache.stats();
        assertThat(stats.hit()).isEqualTo(1L);
        assertThat(stats.pollHit()).isEqualTo(1L);
        // 轮询期间的空读不进 miss，否则命中率会被稀释
        assertThat(stats.miss()).isZero();
        assertThat(classifyCache.hitRate()).isEqualTo(1D);
    }

    @SuppressWarnings("unchecked")
    @DisplayName("轮询超时：返回空但不产生未命中，交给调用方自己调模型")
    @Test
    void pollTimeoutShouldNotPolluteMissCount() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        classifyCache = newCache(redisTemplate);
        when(valueOperations.get(KEY)).thenReturn(null);

        assertThat(classifyCache.awaitResult(KEY, 3, 10L)).isEmpty();

        ClassifyCache.Stats stats = classifyCache.stats();
        assertThat(stats.hit()).isZero();
        assertThat(stats.miss()).isZero();
        assertThat(stats.pollHit()).isZero();
    }

    @SuppressWarnings("unchecked")
    @DisplayName("释放标记时 Redis 报错只记日志，不往外抛")
    @Test
    void releaseMarkShouldSwallowRedisError() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        classifyCache = newCache(redisTemplate);
        when(redisTemplate.delete(KEY)).thenThrow(new IllegalStateException("Redis 连接中断"));

        assertThatCode(() -> classifyCache.releaseMark(KEY)).doesNotThrowAnyException();
        assertThat(classifyCache.stats().error()).isEqualTo(1L);
    }

    @DisplayName("容器里没有 Redis 客户端时，等待与释放都立即返回空，不阻塞")
    @Test
    void missingRedisShouldNeverBlock() {
        classifyCache = newCache(null);

        assertThat(classifyCache.awaitResult(KEY, 3, 50L)).isEmpty();
        assertThatCode(() -> classifyCache.releaseMark(KEY)).doesNotThrowAnyException();
        // 没有缓存能力不算未命中，和 get 的口径保持一致
        assertThat(classifyCache.stats().miss()).isZero();
    }

    @SuppressWarnings("unchecked")
    @DisplayName("写缓存成功返回 true：标记已被结果覆盖，调用方不必再删")
    @Test
    void putSuccessShouldReturnTrue() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        classifyCache = newCache(redisTemplate);

        assertThat(classifyCache.put(KEY, modelResult())).isTrue();
        verify(valueOperations).set(eq(KEY), anyString(), any(Duration.class));
        assertThat(classifyCache.stats().error()).isZero();
    }

    @SuppressWarnings("unchecked")
    @DisplayName("写缓存失败返回 false 且不抛异常：标记还留在 Redis 里，得由调用方处理")
    @Test
    void putFailureShouldReturnFalseWithoutThrowing() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        classifyCache = newCache(redisTemplate);
        doThrow(new IllegalStateException("Redis 连接中断")).when(valueOperations)
            .set(eq(KEY), anyString(), any(Duration.class));

        assertThatCode(() -> {
            assertThat(classifyCache.put(KEY, modelResult())).isFalse();
        }).doesNotThrowAnyException();
        assertThat(classifyCache.stats().error()).isEqualTo(1L);
    }

    @DisplayName("没有 Redis 客户端时写缓存返回 false，不假装写成功了")
    @Test
    void putWithoutRedisShouldReturnFalse() {
        classifyCache = newCache(null);

        assertThat(classifyCache.put(KEY, modelResult())).isFalse();
    }

    /**
     * 造一个正常的模型分类结果
     *
     * @return 分类结果
     */
    private ClassifyResult modelResult() {
        return ClassifyResult
            .ofModel(TicketCategoryEnum.ACCOUNT, TicketPriorityEnum.HIGH, "多人无法登录", 1800);
    }

    /**
     * 构造被 Mockito 接管的缓存实例
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
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 300L);
        ReflectionTestUtils.setField(cache, "processingTtlSeconds", 60L);
        return cache;
    }

    /**
     * 造一份缓存的序列化值
     *
     * @return JSON 字符串
     * @throws Exception 序列化失败
     */
    private String cachedJson() throws Exception {
        return new ObjectMapper().writeValueAsString(new CachedClassify(TicketCategoryEnum.ACCOUNT
            .getValue(), TicketPriorityEnum.HIGH.getValue(), "多人无法登录", false, 2162));
    }
}
