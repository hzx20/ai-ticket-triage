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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;

import java.util.Optional;

/**
 * 分类缓存 Redis 行为测试
 *
 * <p>
 * 和另外三个纯逻辑测试不同，这个类会真的连本地 Redis（默认 127.0.0.1:6379，无密码）。
 * 连不上就整类跳过，不会让构建失败——开发机上没起 Redis 是常态，不能因此卡住构建。
 * </p>
 *
 * <p>
 * 写进去的 key 都带 5 分钟 TTL，测完还会主动清理，不会在本地 Redis 里留垃圾。
 * </p>
 *
 * @author hzx20
 * @since 1.1.0
 */
class ClassifyCacheRedisTest {

    private static final String HOST = "127.0.0.1";

    private static final int PORT = 6379;

    private LettuceConnectionFactory connectionFactory;

    private StringRedisTemplate redisTemplate;

    private ClassifyCache classifyCache;

    private boolean redisAvailable;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(HOST, PORT);
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            redisAvailable = true;
        } catch (Exception e) {
            // 本地没起 Redis，后面的用例全部跳过
            redisAvailable = false;
            return;
        }

        classifyCache = newCache(redisTemplate);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    @DisplayName("写入后能按同一个 key 读出来，且来源被标记为缓存")
    void putThenGetShouldReturnCachedResult() {
        requireRedis();
        String key = ClassifyCache.buildKey(1L, "登不进去", "整个组卡在加载页，密码输对了也进不去");
        ClassifyResult original = ClassifyResult
            .ofModel(TicketCategoryEnum.ACCOUNT, TicketPriorityEnum.HIGH, "多人无法登录", 2162);

        classifyCache.put(key, original);
        Optional<ClassifyResult> cached = classifyCache.get(key);

        assertThat(cached).isPresent();
        assertThat(cached.get().category()).isEqualTo(TicketCategoryEnum.ACCOUNT);
        assertThat(cached.get().priority()).isEqualTo(TicketPriorityEnum.HIGH);
        assertThat(cached.get().reason()).isEqualTo("多人无法登录");
        assertThat(cached.get().source()).isEqualTo(ClassifySource.CACHE);
        assertThat(cached.get().fallback()).isFalse();

        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("key 不存在时返回空，不抛异常")
    void getMissingKeyShouldReturnEmpty() {
        requireRedis();

        assertThat(classifyCache.get(ClassifyCache.buildKey(999L, "不存在的标题", "不存在的描述")))
            .isEmpty();
    }

    @Test
    @DisplayName("抢占计算权：第一次成功，第二次失败")
    void tryLockShouldOnlySucceedOnce() {
        requireRedis();
        String key = ClassifyCache.buildKey(2L, "并发测试", "两个线程同时提交");

        assertThat(classifyCache.tryLock(key)).isTrue();
        assertThat(classifyCache.tryLock(key)).isFalse();

        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("抢占标记存在时按未命中处理，不会把标记当成分类结果返回")
    void processingMarkerShouldBeTreatedAsMiss() {
        requireRedis();
        String key = ClassifyCache.buildKey(3L, "占位测试", "模拟另一个线程正在计算");
        classifyCache.tryLock(key);

        assertThat(classifyCache.get(key)).isEmpty();

        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("写入的 key 带 TTL，不会永久驻留")
    void writtenKeyShouldHaveTtl() {
        requireRedis();
        String key = ClassifyCache.buildKey(4L, "TTL 测试", "确认过期时间已设置");
        classifyCache.put(key, ClassifyResult
            .ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.MEDIUM, "测试", 100));

        Long ttl = redisTemplate.getExpire(key);

        assertThat(ttl).isNotNull().isBetween(1L, 300L);

        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("统计计数随命中、未命中增长")
    void statsShouldCountHitsAndMisses() {
        requireRedis();
        String key = ClassifyCache.buildKey(5L, "统计测试", "验证计数器");
        classifyCache.get(key);
        classifyCache.put(key, ClassifyResult
            .ofModel(TicketCategoryEnum.DATA, TicketPriorityEnum.LOW, "测试", 100));
        classifyCache.get(key);

        ClassifyCache.Stats stats = classifyCache.stats();

        assertThat(stats.miss()).isEqualTo(1L);
        assertThat(stats.hit()).isEqualTo(1L);
        assertThat(classifyCache.hitRate()).isEqualTo(0.5D);

        redisTemplate.delete(key);
    }

    @Test
    @DisplayName("Redis 连不上时全部降级：读返回空、抢锁返回 false、写不抛异常")
    void unreachableRedisShouldDegradeSilently() {
        LettuceConnectionFactory deadFactory = new LettuceConnectionFactory(HOST, 1);
        deadFactory.afterPropertiesSet();
        StringRedisTemplate deadTemplate = new StringRedisTemplate(deadFactory);
        deadTemplate.afterPropertiesSet();
        ClassifyCache deadCache = newCache(deadTemplate);
        String key = ClassifyCache.buildKey(1L, "降级测试", "Redis 挂了");

        try {
            assertThat(deadCache.get(key)).isEmpty();
            assertThat(deadCache.tryLock(key)).isFalse();
            // 写失败不能抛，否则工单分类会被缓存拖垮
            deadCache.put(key, ClassifyResult
                .ofModel(TicketCategoryEnum.OTHER, TicketPriorityEnum.LOW, "测试", 100));
            assertThat(deadCache.stats().error()).isGreaterThan(0L);
        } finally {
            deadFactory.destroy();
        }
    }

    /**
     * 要求本地 Redis 可用
     *
     * <p>
     * 用 Assumptions 而不是直接 return：连不上时测试会被标记为「跳过」，
     * 而不是「通过」。这两种在 CI 报告里长得完全不一样——后者会让看报告的人以为
     * 缓存逻辑被验证过了，实际上一次都没跑。
     * </p>
     */
    private void requireRedis() {
        Assumptions.assumeTrue(redisAvailable, "本地 Redis 不可用，跳过缓存集成测试");
    }

    private ClassifyCache newCache(StringRedisTemplate template) {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("stringRedisTemplate", template);
        ClassifyCache cache = new ClassifyCache(beanFactory
            .getBeanProvider(StringRedisTemplate.class), new ObjectMapper());
        // 这些值平时由 @Value 注入，测试里没有容器，手工设置成和 application 默认值一致
        ReflectionTestUtils.setField(cache, "enabled", true);
        ReflectionTestUtils.setField(cache, "ttlSeconds", 300L);
        ReflectionTestUtils.setField(cache, "processingTtlSeconds", 60L);
        return cache;
    }
}
