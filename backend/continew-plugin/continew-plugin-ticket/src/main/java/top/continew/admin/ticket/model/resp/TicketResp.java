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

import cn.crane4j.annotation.Assemble;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import top.continew.admin.common.base.model.resp.BaseDetailResp;
import top.continew.admin.common.constant.ContainerConstants;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketStatusEnum;

import java.io.Serial;

/**
 * 工单响应参数
 *
 * @author hzx20
 * @since 1.0.0
 */
@Data
@Schema(description = "工单响应参数")
public class TicketResp extends BaseDetailResp {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "标题")
    private String title;

    @Schema(description = "问题描述")
    private String content;

    @Schema(description = "分类编码")
    private String category;

    @Schema(description = "优先级（1：低；2：中；3：高）")
    private Integer priority;

    @Schema(description = "状态（1：待处理；2：处理中；3：已解决；4：已关闭）")
    private Integer status;

    /**
     * 处理人 ID
     *
     * <p>
     * {@code @Assemble} 是框架里数据填充的写法：查询出来后按 ID 批量去用户容器里取昵称，
     * 一次性填充 assigneeName，不会像逐条查库那样产生 N+1 查询。
     * </p>
     */
    @Schema(description = "处理人 ID")
    @Assemble(prop = ":assigneeName", container = ContainerConstants.USER_NICKNAME)
    private Long assigneeId;

    @Schema(description = "处理人名称")
    private String assigneeName;

    @Schema(description = "处理备注")
    private String handleRemark;

    @Schema(description = "AI 判定理由")
    private String aiReason;

    @Schema(description = "是否走了规则兜底")
    private Boolean aiFallback;

    @Schema(description = "AI 调用耗时（毫秒）")
    private Integer aiCostMs;

    /**
     * 分类名称（由编码翻译，不存库）
     *
     * @return 分类中文名称
     */
    @Schema(description = "分类名称")
    public String getCategoryName() {
        return TicketCategoryEnum.parse(this.category).getDescription();
    }

    /**
     * 状态名称（由值翻译，不存库）
     *
     * @return 状态中文名称
     */
    @Schema(description = "状态名称")
    public String getStatusName() {
        if (this.status == null) {
            return "";
        }
        for (TicketStatusEnum item : TicketStatusEnum.values()) {
            if (item.getValue().equals(this.status)) {
                return item.getDescription();
            }
        }
        return "";
    }
}
