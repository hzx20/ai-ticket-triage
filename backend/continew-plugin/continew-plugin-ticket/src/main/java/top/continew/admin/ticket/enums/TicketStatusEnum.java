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
 * 工单状态
 *
 * @author WorkBuddy
 * @since 1.0.0
 */
@Getter
@RequiredArgsConstructor
public enum TicketStatusEnum {

    /**
     * 待处理：刚提交，等人工接手
     */
    PENDING(1, "待处理"),

    /**
     * 处理中
     */
    PROCESSING(2, "处理中"),

    /**
     * 已解决
     */
    RESOLVED(3, "已解决"),

    /**
     * 已关闭
     */
    CLOSED(4, "已关闭");

    private final Integer value;

    private final String description;
}
