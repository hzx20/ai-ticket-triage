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

package top.continew.admin.ticket.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 工单优先级
 *
 * @author hzx20
 * @since 1.0.0
 */
@Getter
@RequiredArgsConstructor
public enum TicketPriorityEnum {

    /**
     * 低
     */
    LOW(1, "低"),

    /**
     * 中
     */
    MEDIUM(2, "中"),

    /**
     * 高
     */
    HIGH(3, "高");

    private final Integer value;

    private final String description;

    /**
     * 根据大模型返回的等级解析优先级，无法识别时降级为「中」
     *
     * <p>
     * 这里默认取「中」而不是「低」：模型输出异常时，宁可让人多看一眼，也不要把问题沉底。
     * </p>
     *
     * @param value 优先级
     * @return 优先级枚举
     */
    public static TicketPriorityEnum parse(Integer value) {
        if (value == null) {
            return MEDIUM;
        }
        for (TicketPriorityEnum item : values()) {
            if (item.value.equals(value)) {
                return item;
            }
        }
        return MEDIUM;
    }
}
