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

import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;

/**
 * 缓存中存放的分类结果
 *
 * <p>
 * 没有直接往 Redis 里塞 {@link ClassifyResult}，是为了把「存什么」和「怎么用」解耦：
 * </p>
 * <ol>
 * <li>{@link ClassifyResult} 里带枚举，Jackson 存的是枚举名（ACCOUNT），而模型输出和数据库里
 * 用的是 value（account）。中间隔一层 DTO，序列化格式就由我们说了算，不怕枚举改名；</li>
 * <li>缓存里不该存 source 字段。存进去的是「结果」，读出来必须标记成 CACHE，
 * 如果连 source 一起存，读出来还是 MODEL，等于自己骗自己；</li>
 * <li>将来缓存结构要加字段（比如加一版提示词编号），只改这里，不影响业务侧的 record。</li>
 * </ol>
 *
 * @param category 分类编码，与 {@link TicketCategoryEnum#getValue()} 一致
 * @param priority 优先级数值，与 {@link TicketPriorityEnum#getValue()} 一致
 * @param reason   判定理由
 * @param fallback 原始判定是否为规则兜底
 * @param costMs   原始调用的耗时（毫秒）
 * @author WorkBuddy
 * @since 1.1.0
 */
public record CachedClassify(String category,
    Integer priority,
    String reason,
    boolean fallback,
    int costMs) {

    /**
     * 由分类结果生成缓存值
     *
     * @param result 分类结果
     * @return 缓存值
     */
    public static CachedClassify from(ClassifyResult result) {
        return new CachedClassify(result.category().getValue(), result.priority().getValue(), result
            .reason(), result.fallback(), result.costMs());
    }

    /**
     * 还原成业务侧的分类结果
     *
     * <p>
     * 用枚举自己的 parse 方法还原，而不是 Enum.valueOf：缓存里万一存了脏数据，
     * parse 会降级到「其他 / 中」并继续跑，valueOf 会直接抛异常让整条链路失败。
     * 这里的原则和模型输出解析保持一致——宁可判得粗一点，也不能让工单卡住。
     * </p>
     *
     * @return 分类结果，来源标记为缓存
     */
    public ClassifyResult toResult() {
        return ClassifyResult.ofCache(TicketCategoryEnum.parse(category), TicketPriorityEnum
            .parse(priority), reason, fallback, costMs);
    }
}
