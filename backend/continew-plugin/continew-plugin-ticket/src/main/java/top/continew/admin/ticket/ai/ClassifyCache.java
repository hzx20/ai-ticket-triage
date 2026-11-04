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

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 分类结果短缓存
 *
 * <p>
 * 解决两个真实存在的问题：同一份工单内容被重复提交时会重复花一次模型调用（约 0.001 元），
 * 而且两次调用可能给出不一致的分类结果。缓存把这两个问题一起消掉。
 * </p>
 *
 * <p>
 * 三条设计原则，任何改动都不能破：
 * </p>
 * <ol>
 * <li><b>缓存是加速器，不是依赖。</b> Redis 连不上、序列化失败、Bean 不存在，
 * 一律退化为「直接调模型」，异常只记日志不往外抛。工单分类不能因为缓存挂了而失败。</li>
 * <li><b>只有可信结果才写缓存。</b> 走规则兜底的结果不写——模型可能是临时故障（限流、超时），
 * 把兜底结果缓存 5 分钟，等于让这段时间内所有同内容工单都用错误分类。</li>
 * <li><b>用 SET NX 抢计算权，而不是「先 get 再 set」。</b> 两条命令不是原子的，
 * 并发下两个线程会同时判空、同时调模型，等于没防住。抢到的一方是「唯一有权写这条缓存的人」，
 * 抢不到的一方用 {@link #awaitResult} 短轮询等结果，等不到才自己算。</li>
 * </ol>
 *
 * @author hzx20
 * @since 1.1.0
 */
@Slf4j
@Component
public class ClassifyCache {

    /**
     * key 前缀，统一在这里定义，便于按前缀统计命中率、批量排查和清理
     */
    public static final String KEY_PREFIX = "triage:cls:";

    /**
     * 每日命中计数器的前缀，用于跨进程累计（本地 AtomicLong 重启就归零）
     */
    private static final String HIT_COUNTER_PREFIX = "triage:cls:hit:";

    /**
     * 抢占标记。写入这个值表示「已经有线程正在算这条工单」，其他线程读到它按未命中处理
     */
    private static final String PROCESSING = "__PROCESSING__";

    /**
     * 参与哈希的正文最大长度。超长工单只取前 2000 字，
     * 保证 key 计算不会成为新的性能点（正文再长也不影响分类判断）
     */
    private static final int MAX_HASH_CONTENT_LENGTH = 2000;

    /**
     * 标题和正文之间的分隔符，防止「标题 ab + 正文 c」和「标题 a + 正文 bc」算出同一个哈希
     */
    private static final char SEPARATOR = '\u0001';

    private static final DateTimeFormatter DAY_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;

    private final ObjectMapper objectMapper;

    /**
     * 总开关。关掉后完全退化为「每次都调模型」，用于排查「问题是不是缓存引起的」
     */
    @Value("${continew.ticket.cache.enabled:true}")
    private boolean enabled = true;

    /**
     * 结果缓存有效期（秒）。300 秒的依据：工单重复提交集中在几十秒内，
     * 5 分钟能覆盖绝大多数场景；再长会让「改了描述重新提交」也命中旧结果
     */
    @Value("${continew.ticket.cache.ttl-seconds:300}")
    private long ttlSeconds = 300;

    /**
     * 抢占标记的有效期（秒）。故意比结果缓存短得多：模型一旦挂掉，
     * 标记最多挡 60 秒就自动消失，不会把后续请求一直挡在门外
     */
    @Value("${continew.ticket.cache.processing-ttl-seconds:60}")
    private long processingTtlSeconds = 60;

    private final AtomicLong hitCount = new AtomicLong();

    private final AtomicLong missCount = new AtomicLong();

    private final AtomicLong lockMissCount = new AtomicLong();

    private final AtomicLong errorCount = new AtomicLong();

    /**
     * 轮询重读命中次数
     *
     * <p>
     * 这是 hitCount 的子集（轮询命中同样算命中，也计入 hitCount），单独计数只为了回答一个问题：
     * 「抢锁失败之后，等待真的换来了结果吗」。这个值长期接近 lockMissCount，说明并发重复提交确实存在
     * 且轮询收益很高；长期接近 0，说明抢不到锁基本等于别人也失败了，等待纯属浪费时间，应该把等待预算调小。
     * </p>
     */
    private final AtomicLong pollHitCount = new AtomicLong();

    public ClassifyCache(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
        ObjectMapper objectMapper) {
        this.redisTemplateProvider = redisTemplateProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 生成缓存 key
     *
     * <p>
     * 结构是 {@code triage:cls:{tenantId}:{sha256 前 128 位}}。租户 ID 放在前缀里而不是哈希里，
     * 好处是排查时能直接按租户扫描，也避免不同租户的 key 混在一起。
     * </p>
     *
     * <p>
     * 做静态方法是为了能脱离 Spring 容器单测——key 一旦算错，缓存就永远不命中，
     * 而且不会有任何报错，属于最难发现的那类 bug。
     * </p>
     *
     * @param tenantId 租户 ID，为空时按 0 处理
     * @param title    工单标题
     * @param content  工单正文
     * @return 缓存 key
     */
    public static String buildKey(Long tenantId, String title, String content) {
        long tenant = tenantId == null ? 0L : tenantId;
        String safeTitle = title == null ? "" : title.trim();
        String safeContent = content == null ? "" : content.trim();
        if (safeContent.length() > MAX_HASH_CONTENT_LENGTH) {
            safeContent = safeContent.substring(0, MAX_HASH_CONTENT_LENGTH);
        }
        return KEY_PREFIX + tenant + ":" + sha256(safeTitle + SEPARATOR + safeContent);
    }

    /**
     * 读缓存
     *
     * @param key 缓存 key
     * @return 命中时返回分类结果，未命中、值为抢占标记、Redis 异常时都返回空
     */
    public Optional<ClassifyResult> get(String key) {
        return read(key, true);
    }

    /**
     * 短轮询等别的线程把结果算出来
     *
     * <p>
     * 只在「没抢到计算权」时调用。原始需求是「抢不到就自己调模型」，但那样 SET NX 只起到
     * 计数作用，没有任何排他效果：拿锁的一方在算，没拿锁的一方立刻又算一遍，同一份工单
     * 被算两次、花两次钱，还可能写出两个不一致的分类结果。加一层短轮询，把「抢不到锁」
     * 变成「等一小会儿，大概率能白拿结果」。
     * </p>
     *
     * <p>
     * 等待预算是硬上限，不是目标值：最多读 {@code maxAttempts} 次、相邻两次之间睡
     * {@code intervalMillis} 毫秒，读满就返回空，由调用方自己调模型。这里绝不阻塞等锁、
     * 也不抛异常——分类链路的保底永远是「自己算一次」，等待只是省钱的手段。
     * </p>
     *
     * <p>
     * 计数口径：轮询期间的重读命中算命中（计入 {@link #hitCount}，并额外计入
     * {@link #pollHitCount}）；轮询期间读到的空值<b>不算未命中</b>。理由是这些空读不是
     * 「缓存没这条数据」，而是「缓存正在被写」，把它算进 miss 会让命中率凭空变低，
     * 反而看不出缓存到底有没有用。
     * </p>
     *
     * @param key            缓存 key
     * @param maxAttempts    最多读几次，小于等于 0 时直接返回空
     * @param intervalMillis 两次重读之间的间隔（毫秒）
     * @return 等到结果时返回，等不到或 Redis 不可用时返回空
     */
    public Optional<ClassifyResult> awaitResult(String key, int maxAttempts, long intervalMillis) {
        if (!enabled || maxAttempts <= 0) {
            return Optional.empty();
        }
        // 没有 Redis 客户端就别等了：等待的前提是「别人正在写」，写不了就不存在等到的可能
        if (redisTemplateProvider.getIfAvailable() == null) {
            return Optional.empty();
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (attempt > 1 && !sleepQuietly(intervalMillis)) {
                // 线程被中断（应用关闭、上游取消），立刻放弃等待，交给调用方自己算，保证有结果
                return Optional.empty();
            }
            long errorsBefore = errorCount.get();
            Optional<ClassifyResult> result = read(key, false);
            if (result.isPresent()) {
                pollHitCount.incrementAndGet();
                return result;
            }
            if (errorCount.get() > errorsBefore) {
                // 这次重读本身失败了（Redis 连不上、值反序列化不了），再等下去只是重复同一个失败
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 读缓存的内部实现
     *
     * @param key       缓存 key
     * @param countMiss 未命中是否计入 missCount。轮询重读传 false，避免把「正在写入」算成「没有数据」
     * @return 命中时返回分类结果，否则返回空
     */
    private Optional<ClassifyResult> read(String key, boolean countMiss) {
        if (!enabled) {
            return Optional.empty();
        }
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            // 不是「未命中」，是「根本没有缓存能力」，所以不计入 miss，避免污染命中率
            return Optional.empty();
        }
        try {
            long start = System.currentTimeMillis();
            String raw = redis.opsForValue().get(key);
            if (raw == null || PROCESSING.equals(raw)) {
                if (countMiss) {
                    missCount.incrementAndGet();
                }
                return Optional.empty();
            }
            CachedClassify cached = objectMapper.readValue(raw, CachedClassify.class);
            hitCount.incrementAndGet();
            increaseDailyHitCounter(redis);
            // 记真实读取耗时（通常 1-5 毫秒），不是缓存的原始模型耗时
            return Optional.of(cached.toResult((int) (System.currentTimeMillis() - start)));
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("读取分类缓存失败，降级为直接调用模型。key={}, 原因={}", key, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 睡一小会儿，不抛异常
     *
     * <p>
     * 被中断时恢复中断标记再返回 false：吞掉中断会让线程池关闭时的取消信号失效，
     * 上层（尤其是 Spring 的优雅停机）就再也叫不动这个线程了。
     * </p>
     *
     * @param millis 睡眠时长（毫秒）
     * @return true 表示正常睡完，false 表示被中断
     */
    private boolean sleepQuietly(long millis) {
        if (millis <= 0) {
            return true;
        }
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 抢占计算权
     *
     * <p>
     * 抢不到不是错误，返回值必须被调用方用起来——这个返回值是唯一能区分「我是计算者」和
     * 「我是等待者」的信号。早期版本调用时没接返回值，结果两条路径行为完全一致，
     * 标记退化成纯粹的计数器，SET NX 白写了。
     * </p>
     *
     * <p>
     * 抢不到时调用方先走 {@link #awaitResult} 短轮询，等不到才自己调模型。不在这里阻塞等锁：
     * 一个慢请求就能把线程池里的线程全占住，代价远大于多调一次模型。
     * </p>
     *
     * @param key 缓存 key
     * @return true 表示抢到，false 表示已有其他线程在算或 Redis 不可用
     */
    public boolean tryLock(String key) {
        if (!enabled) {
            return false;
        }
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return false;
        }
        try {
            Boolean ok = redis.opsForValue()
                .setIfAbsent(key, PROCESSING, Duration.ofSeconds(processingTtlSeconds));
            if (Boolean.TRUE.equals(ok)) {
                return true;
            }
            lockMissCount.incrementAndGet();
            return false;
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("抢占分类计算权失败，本次直接调用模型。key={}, 原因={}", key, e.getMessage());
            return false;
        }
    }

    /**
     * 写入缓存
     *
     * @param key    缓存 key
     * @param result 分类结果
     */
    public void put(String key, ClassifyResult result) {
        if (!enabled) {
            return;
        }
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            String value = objectMapper.writeValueAsString(CachedClassify.from(result));
            redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("写入分类缓存失败，不影响本次分类结果。key={}, 原因={}", key, e.getMessage());
        }
    }

    /**
     * 主动释放抢占标记
     *
     * <p>
     * 只在「自己抢到了锁、但最终拿到的是兜底结果」时调用。兜底结果不写缓存，如果不删标记，
     * 这个 key 会被 {@code __PROCESSING__} 占满整个标记 TTL（默认 60 秒）。这 60 秒里
     * 所有同内容工单都会抢锁失败，然后白等一轮轮询预算才自己去调模型——一次兜底，拖慢
     * 后续一分钟内的所有重复提交。
     * </p>
     *
     * <p>
     * <b>为什么直接 delete 不会误删别人的标记：</b>标记的 TTL 是 60 秒，而本线程持锁期间只做一件事
     * ——调一次模型，模型那边连接超时 5 秒、读取超时 20 秒，单次调用最长 25 秒，远小于 60 秒。
     * 也就是说，只要还在本线程的处理窗口内，这个 key 一定没过期、一定还是本线程写进去的那个
     * {@code __PROCESSING__}，不存在「标记过期后被别的线程抢走、我又把人家的删了」的时间窗。
     * 代价上限是：万一将来有人把超时配到 60 秒以上，这里就会误删，所以模型超时和标记 TTL
     * 是必须一起看的两个数，改一个就要回头看另一个。
     * </p>
     *
     * @param key 缓存 key
     */
    public void releaseMark(String key) {
        if (!enabled) {
            return;
        }
        StringRedisTemplate redis = redisTemplateProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            redis.delete(key);
        } catch (Exception e) {
            // 删不掉最坏只是让后续请求多等一轮，不能让分类失败，所以只记日志
            errorCount.incrementAndGet();
            log.warn("释放分类抢占标记失败，该 key 将在 TTL 到期后自动失效。key={}, 原因={}", key, e
                .getMessage());
        }
    }

    /**
     * 当前实例的缓存统计
     *
     * <p>
     * 全部是进程内计数，应用重启归零。这是刻意的：统计接口要回答的是
     * 「这个实例现在缓存工作得怎么样」，跨实例汇总交给 Redis 日计数器。
     * </p>
     *
     * @return 统计快照
     */
    public Stats stats() {
        return new Stats(hitCount.get(),
            missCount.get(),
            lockMissCount.get(),
            errorCount.get(),
            pollHitCount.get());
    }

    /**
     * 命中率，保留四位小数。总请求为 0 时返回 0，避免前端拿到 NaN
     *
     * @return 命中率
     */
    public double hitRate() {
        long hit = hitCount.get();
        long total = hit + missCount.get();
        if (total == 0) {
            return 0D;
        }
        return Math.round(hit * 10000D / total) / 10000D;
    }

    /**
     * 累加 Redis 日计数器
     *
     * <p>
     * 失败直接忽略：这个计数器只服务于运维观察，没有任何业务逻辑依赖它，
     * 为它记一条 warn 日志都属于噪音。
     * </p>
     *
     * @param redis Redis 客户端
     */
    private void increaseDailyHitCounter(StringRedisTemplate redis) {
        try {
            String counterKey = HIT_COUNTER_PREFIX + LocalDate.now().format(DAY_FORMATTER);
            Long value = redis.opsForValue().increment(counterKey);
            if (value != null && value == 1L) {
                // 第一次创建当天计数器时才设过期，避免每次命中都多一次 EXPIRE 往返
                redis.expire(counterKey, Duration.ofDays(2));
            }
        } catch (Exception ignored) {
            // 计数器失败不影响主流程，见方法注释
        }
    }

    /**
     * SHA-256 摘要，取前 16 字节转十六进制（32 字符）
     *
     * <p>
     * 取前 128 位而不是完整的 256 位，是为了控制 Redis key 长度；
     * 按每秒几条工单的量级，128 位碰撞概率可以忽略。
     * </p>
     *
     * @param text 原文
     * @return 32 位十六进制字符串
     */
    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (int i = 0; i < 16; i++) {
                hex.append(String.format("%02x", bytes[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论走不到这里；真走到了也不能让分类失败，
            // 退化为 hashCode 只会降低命中率，不会算错结果
            return Integer.toHexString(text.hashCode());
        }
    }

    /**
     * 缓存统计快照
     *
     * @param hit     命中次数（含轮询命中）
     * @param miss    未命中次数（不含「Redis 不可用」，也不含轮询期间的空读）
     * @param lockMiss 抢占计算权失败次数
     * @param error    Redis 操作异常次数
     * @param pollHit 轮询重读命中次数（hit 的子集）
     */
    public record Stats(long hit, long miss, long lockMiss, long error, long pollHit) {
    }
}
