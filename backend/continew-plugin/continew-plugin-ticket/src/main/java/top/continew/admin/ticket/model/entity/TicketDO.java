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

package top.continew.admin.ticket.model.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import top.continew.admin.common.base.model.entity.TenantBaseDO;
import top.continew.starter.extension.crud.annotation.DictModel;

import java.io.Serial;

/**
 * 工单实体
 *
 * <p>
 * 继承 TenantBaseDO 而不是 BaseDO：多一层 tenantId。这个项目开启了行级多租户隔离
 * （continew-starter.tenant.isolation-level = LINE），业务表带 tenant_id 后，
 * 框架会自动在每个查询后面拼上租户条件，不同租户的工单互相看不到。
 * </p>
 *
 * @author WorkBuddy
 * @since 1.0.0
 */
@Data
@DictModel(valueKey = "category")
@TableName("ticket")
public class TicketDO extends TenantBaseDO {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 标题
     */
    private String title;

    /**
     * 问题描述
     */
    private String content;

    /**
     * 分类编码（由 AI 判定，规则兜底时也写这里）
     */
    private String category;

    /**
     * 优先级（1：低；2：中；3：高）
     */
    private Integer priority;

    /**
     * 状态（1：待处理；2：处理中；3：已解决；4：已关闭）
     */
    private Integer status;

    /**
     * 处理人 ID
     */
    private Long assigneeId;

    /**
     * 处理备注
     */
    private String handleRemark;

    /**
     * AI 判定理由（给人看的，也是排查误判时的第一手依据）
     */
    private String aiReason;

    /**
     * 是否走了规则兜底（模型不可用或超时时为 true）
     */
    private Boolean aiFallback;

    /**
     * AI 调用耗时（毫秒）
     *
     * <p>
     * 缓存命中时记的是「读 Redis 的实际耗时」（通常几毫秒），不是当初调模型的耗时。
     * 后者会让「平均耗时」这个指标看不出缓存有任何收益。
     * </p>
     */
    private Integer aiCostMs;

    /**
     * AI 结果来源（MODEL：模型判定；RULE：规则兜底；CACHE：缓存命中）
     *
     * <p>
     * 只看 category 和 priority 看不出这条记录是怎么来的。按来源分组统计，
     * 才能回答「缓存到底省了多少时间」这类问题。
     * </p>
     */
    private String aiSource;
}
