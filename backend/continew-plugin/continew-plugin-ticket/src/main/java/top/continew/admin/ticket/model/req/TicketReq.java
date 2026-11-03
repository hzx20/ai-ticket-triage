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

package top.continew.admin.ticket.model.req;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.io.Serial;
import java.io.Serializable;

/**
 * 工单创建或修改请求参数
 *
 * <p>
 * 创建时只有 title 和 content 必填，分类和优先级由 AI 判定；
 * 但两者留了口子给人工纠正——模型会判错，业务上必须允许人工覆盖。
 * </p>
 *
 * @author hzx20
 * @since 1.0.0
 */
@Data
@Schema(description = "工单创建或修改请求参数")
public class TicketReq implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 标题
     */
    @Schema(description = "标题", example = "后台登录一直提示验证码错误")
    @NotBlank(message = "标题不能为空")
    @Size(max = 100, message = "标题长度不能超过 {max} 个字符")
    private String title;

    /**
     * 问题描述
     */
    @Schema(description = "问题描述", example = "输入正确的验证码仍然提示错误，换浏览器也一样")
    @NotBlank(message = "问题描述不能为空")
    @Size(max = 2000, message = "问题描述长度不能超过 {max} 个字符")
    private String content;

    /**
     * 分类编码（留空则由 AI 判定）
     */
    @Schema(description = "分类编码（留空则由 AI 判定）", example = "fault")
    @Size(max = 30, message = "分类编码长度不能超过 {max} 个字符")
    private String category;

    /**
     * 优先级（留空则由 AI 判定）
     */
    @Schema(description = "优先级（1：低；2：中；3：高，留空则由 AI 判定）", example = "3")
    private Integer priority;

    /**
     * 状态
     */
    @Schema(description = "状态（1：待处理；2：处理中；3：已解决；4：已关闭）", example = "1")
    private Integer status;

    /**
     * 处理人 ID
     */
    @Schema(description = "处理人 ID", example = "1")
    private Long assigneeId;

    /**
     * 处理备注
     */
    @Schema(description = "处理备注", example = "已引导用户清理浏览器缓存后恢复正常")
    @Size(max = 500, message = "处理备注长度不能超过 {max} 个字符")
    private String handleRemark;
}
