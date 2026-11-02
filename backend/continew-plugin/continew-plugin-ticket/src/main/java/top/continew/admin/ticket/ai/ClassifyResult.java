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
 * @author WorkBuddy
 * @since 1.0.0
 */
public record ClassifyResult(TicketCategoryEnum category,
    TicketPriorityEnum priority,
    String reason,
    boolean fallback,
    int costMs) {

    /**
     * 构造模型判定结果
     */
    public static ClassifyResult ofModel(TicketCategoryEnum category,
        TicketPriorityEnum priority,
        String reason,
        int costMs) {
        return new ClassifyResult(category, priority, truncate(reason), false, costMs);
    }

    /**
     * 构造规则兜底结果
     */
    public static ClassifyResult ofRule(TicketCategoryEnum category,
        TicketPriorityEnum priority,
        String reason,
        int costMs) {
        return new ClassifyResult(category, priority, truncate(reason), true, costMs);
    }

    /**
     * 理由字段入库长度上限 500，模型偶尔会写很长，这里主动截断，避免插入时报错
     */
    private static String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() <= 480 ? reason : reason.substring(0, 480) + "...";
    }
}
