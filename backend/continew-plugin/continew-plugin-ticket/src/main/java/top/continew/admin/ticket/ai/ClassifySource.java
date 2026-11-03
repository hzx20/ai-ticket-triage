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

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 分类结果的来源
 *
 * <p>
 * 单看 category 和 priority 看不出这条记录是怎么来的，而面试和排障都需要这个信息。
 * 三种来源的信任级别不一样：模型判定可信度最高，规则兜底只保证「有个大致正确的筐」，
 * 缓存命中则是复用了之前某次判定的结果，本身不产生新信息。
 * </p>
 *
 * <p>
 * 注意它和 {@code fallback} 不是一回事：缓存命中的那条记录，可能是当初模型判的，
 * 也可能是当初规则兜底的。所以两个字段要分开存。
 * </p>
 *
 * @author WorkBuddy
 * @since 1.1.0
 */
@Getter
@RequiredArgsConstructor
public enum ClassifySource {

    /**
     * 本次调用大模型判定
     */
    MODEL("模型判定"),

    /**
     * 本次走关键词规则兜底
     */
    RULE("规则兜底"),

    /**
     * 直接复用缓存中的历史结果，没有产生新的模型调用
     */
    CACHE("缓存命中");

    private final String description;
}
