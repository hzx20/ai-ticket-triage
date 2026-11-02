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
 * 工单分类
 *
 * <p>
 * 分类值同时会作为大模型输出 JSON 里的枚举取值，因此编码需要和提示词里的候选列表严格一致。
 * </p>
 *
 * @author WorkBuddy
 * @since 1.0.0
 */
@Getter
@RequiredArgsConstructor
public enum TicketCategoryEnum {

    /**
     * 账号与权限
     */
    ACCOUNT("account", "账号与权限"),

    /**
     * 系统故障
     */
    FAULT("fault", "系统故障"),

    /**
     * 功能需求
     */
    FEATURE("feature", "功能需求"),

    /**
     * 数据问题
     */
    DATA("data", "数据问题"),

    /**
     * 使用咨询
     */
    CONSULT("consult", "使用咨询"),

    /**
     * 其他
     */
    OTHER("other", "其他");

    private final String value;

    private final String description;

    /**
     * 根据大模型返回的字符串解析分类，无法识别时降级为「其他」
     *
     * @param value 分类编码
     * @return 分类枚举
     */
    public static TicketCategoryEnum parse(String value) {
        if (value == null) {
            return OTHER;
        }
        String normalized = value.trim().toLowerCase();
        for (TicketCategoryEnum item : values()) {
            if (item.value.equals(normalized) || item.name().equalsIgnoreCase(normalized)) {
                return item;
            }
        }
        return OTHER;
    }
}
