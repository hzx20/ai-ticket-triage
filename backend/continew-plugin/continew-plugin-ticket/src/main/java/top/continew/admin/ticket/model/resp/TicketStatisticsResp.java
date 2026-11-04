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

package top.continew.admin.ticket.model.resp;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 工单统计
 *
 * <p>
 * 这几个指标是判断 AI 分类「是否可信」的依据：
 * 兜底率过高说明模型不可用；平均耗时过长说明该加缓存或换更快的模型。
 * </p>
 *
 * @author hzx20
 * @since 1.0.0
 */
@Data
@Builder
@Schema(description = "工单统计")
public class TicketStatisticsResp implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "工单总数")
    private Long total;

    @Schema(description = "待处理数量")
    private Long pendingCount;

    @Schema(description = "各分类数量")
    private Map<String, Long> categoryCount;

    @Schema(description = "各优先级数量")
    private Map<Integer, Long> priorityCount;

    @Schema(description = "规则兜底数量")
    private Long fallbackCount;

    @Schema(description = "规则兜底占比（0-1）")
    private Double fallbackRate;

    @Schema(description = "AI 平均耗时（毫秒，含所有来源）")
    private Double avgCostMs;

    @Schema(description = "各来源数量（MODEL / RULE / CACHE）")
    private Map<String, Long> sourceCount;

    @Schema(description = "模型判定平均耗时（毫秒）")
    private Double modelAvgCostMs;

    @Schema(description = "缓存命中平均耗时（毫秒）")
    private Double cacheAvgCostMs;

    @Schema(description = "缓存省下的平均耗时（毫秒）= 模型平均 - 缓存平均")
    private Double savedCostMs;

    @Schema(description = "分类缓存命中次数（当前实例，重启归零）")
    private Long cacheHitCount;

    @Schema(description = "分类缓存未命中次数（当前实例，重启归零）")
    private Long cacheMissCount;

    @Schema(description = "分类缓存命中率（0-1）")
    private Double cacheHitRate;

    @Schema(description = "抢占计算权失败次数（说明存在并发重复提交）")
    private Long cacheLockMissCount;

    @Schema(description = "抢锁失败后靠轮询等到结果的次数（判断轮询是否值得保留）")
    private Long cachePollHitCount;

    @Schema(description = "缓存操作异常次数（Redis 不可用时会持续增长）")
    private Long cacheErrorCount;
}
