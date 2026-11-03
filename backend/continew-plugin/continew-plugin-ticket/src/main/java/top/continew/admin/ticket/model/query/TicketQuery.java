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

package top.continew.admin.ticket.model.query;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import top.continew.starter.data.annotation.Query;
import top.continew.starter.data.enums.QueryType;

import java.io.Serial;
import java.io.Serializable;

/**
 * 工单查询条件
 *
 * <p>
 * 字段上的 {@code @Query} 注解由框架的查询条件解析器自动翻译成 SQL 的 where 子句，
 * 不需要手写 XML 或 Wrapper。
 * </p>
 *
 * @author hzx20
 * @since 1.0.0
 */
@Data
@Schema(description = "工单查询条件")
public class TicketQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 关键词（同时匹配标题和内容）
     */
    @Schema(description = "关键词", example = "登录不上")
    @Query(columns = {"title", "content"}, type = QueryType.LIKE)
    private String keyword;

    /**
     * 分类编码
     */
    @Schema(description = "分类编码", example = "fault")
    @Query(type = QueryType.EQ)
    private String category;

    /**
     * 优先级
     */
    @Schema(description = "优先级（1：低；2：中；3：高）", example = "3")
    @Query(type = QueryType.EQ)
    private Integer priority;

    /**
     * 状态
     */
    @Schema(description = "状态（1：待处理；2：处理中；3：已解决；4：已关闭）", example = "1")
    @Query(type = QueryType.EQ)
    private Integer status;

    /**
     * 是否走规则兜底
     */
    @Schema(description = "是否走规则兜底", example = "false")
    @Query(type = QueryType.EQ)
    private Boolean aiFallback;
}
