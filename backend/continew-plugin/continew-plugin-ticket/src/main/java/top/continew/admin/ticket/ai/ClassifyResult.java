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
 * 工单分类结果
 *
 * @param category 分类
 * @param priority 优先级
 * @param reason   判定理由
 * @param fallback 是否由规则兜底产生（true 表示大模型这次没用上）
 * @param costMs   耗时（毫秒）
 * @param source   结果来源（模型判定 / 规则兜底 / 缓存命中）
 * @author WorkBuddy
 * @since 1.0.0
 */
public record ClassifyResult(TicketCategoryEnum category,
    TicketPriorityEnum priority,
    String reason,
    boolean fallback,
    int costMs,
    ClassifySource source) {

    /**
     * 构造模型判定结果
     *
     * @param category 分类
     * @param priority 优先级
     * @param reason   判定理由
     * @param costMs   耗时（毫秒）
     * @return 分类结果
     */
    public static ClassifyResult ofModel(TicketCategoryEnum category,
        TicketPriorityEnum priority,
        String reason,
        int costMs) {
        return new ClassifyResult(category, priority, truncate(reason), false, costMs,
            ClassifySource.MODEL);
    }

    /**
     * 构造规则兜底结果
     *
     * @param category 分类
     * @param priority 优先级
     * @param reason   兜底原因
     * @param costMs   耗时（毫秒）
     * @return 分类结果
     */
    public static ClassifyResult ofRule(TicketCategoryEnum category,
        TicketPriorityEnum priority,
        String reason,
        int costMs) {
        return new ClassifyResult(category, priority, truncate(reason), true, costMs,
            ClassifySource.RULE);
    }

    /**
     * 构造缓存命中结果
     *
     * <p>
     * 注意 fallback 跟着原始结果走，不强制改成 false。一条当初由规则兜底产生的结果，
     * 即使是从缓存里读出来的，它依然是兜底结果——这个事实不会因为走了缓存而改变。
     * </p>
     *
     * @param category 分类
     * @param priority 优先级
     * @param reason   原始判定理由
     * @param fallback 原始结果是否为规则兜底
     * @param costMs   原始调用的耗时（保留下来便于对比缓存带来的收益）
     * @return 分类结果
     */
    public static ClassifyResult ofCache(TicketCategoryEnum category,
        TicketPriorityEnum priority,
        String reason,
        boolean fallback,
        int costMs) {
        return new ClassifyResult(category, priority, truncate(reason), fallback, costMs,
            ClassifySource.CACHE);
    }

    /**
     * 理由字段入库长度上限 500，模型偶尔会写很长，这里主动截断，避免插入时报错
     *
     * @param reason 原始理由
     * @return 截断后的理由
     */
    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() <= 480 ? reason : reason.substring(0, 480) + "...";
    }
}
