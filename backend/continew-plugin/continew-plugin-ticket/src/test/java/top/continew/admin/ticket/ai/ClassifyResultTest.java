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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;

/**
 * 分类结果与缓存值测试
 *
 * @author WorkBuddy
 * @since 1.1.0
 */
class ClassifyResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("模型结果来源为 MODEL，不算兜底")
    void modelResultShouldNotBeFallback() {
        ClassifyResult result = ClassifyResult
            .ofModel(TicketCategoryEnum.ACCOUNT, TicketPriorityEnum.HIGH, "多人无法登录", 1200);

        assertThat(result.source()).isEqualTo(ClassifySource.MODEL);
        assertThat(result.fallback()).isFalse();
    }

    @Test
    @DisplayName("规则结果来源为 RULE，且标记兜底")
    void ruleResultShouldBeFallback() {
        ClassifyResult result = ClassifyResult
            .ofRule(TicketCategoryEnum.OTHER, TicketPriorityEnum.MEDIUM, "未配置密钥", 3);

        assertThat(result.source()).isEqualTo(ClassifySource.RULE);
        assertThat(result.fallback()).isTrue();
    }

    @Test
    @DisplayName("缓存命中时来源改为 CACHE，但不篡改原始的兜底标记")
    void cacheResultShouldKeepOriginalFallbackFlag() {
        ClassifyResult fromRule = ClassifyResult
            .ofCache(TicketCategoryEnum.OTHER, TicketPriorityEnum.MEDIUM, "原始为规则判定", true, 5);
        ClassifyResult fromModel = ClassifyResult
            .ofCache(TicketCategoryEnum.FAULT, TicketPriorityEnum.HIGH, "原始为模型判定", false, 1800);

        assertThat(fromRule.source()).isEqualTo(ClassifySource.CACHE);
        // 一条当初由规则兜底的结果，从缓存读出来依然是兜底结果，这个事实不因走缓存而改变
        assertThat(fromRule.fallback()).isTrue();
        assertThat(fromModel.fallback()).isFalse();
    }

    @Test
    @DisplayName("超长理由被截断到入库安全长度")
    void longReasonShouldBeTruncated() {
        String longReason = "很长的理由".repeat(200);

        ClassifyResult result = ClassifyResult
            .ofModel(TicketCategoryEnum.DATA, TicketPriorityEnum.MEDIUM, longReason, 100);

        assertThat(result.reason()).endsWith("...").hasSize(483);
    }

    @Test
    @DisplayName("理由为空时转成空字符串，不让 null 进数据库")
    void nullReasonShouldBecomeEmptyString() {
        assertThat(ClassifyResult.ofModel(TicketCategoryEnum.DATA, TicketPriorityEnum.LOW, null, 1)
            .reason()).isEmpty();
    }

    @Test
    @DisplayName("缓存值序列化后能无损还原，且来源标记为缓存")
    void cachedValueShouldSurviveSerialization() throws Exception {
        ClassifyResult original = ClassifyResult
            .ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.HIGH, "接口 500", 1938);

        String json = objectMapper.writeValueAsString(CachedClassify.from(original));
        ClassifyResult restored = objectMapper.readValue(json, CachedClassify.class).toResult();

        assertThat(restored.category()).isEqualTo(original.category());
        assertThat(restored.priority()).isEqualTo(original.priority());
        assertThat(restored.reason()).isEqualTo(original.reason());
        assertThat(restored.costMs()).isEqualTo(original.costMs());
        assertThat(restored.source()).isEqualTo(ClassifySource.CACHE);
    }

    @Test
    @DisplayName("缓存里存了脏分类时降级为「其他」，不让整条链路失败")
    void dirtyCachedCategoryShouldDegradeInsteadOfThrowing() {
        CachedClassify dirty = new CachedClassify("不存在的分类", 99, "脏数据", false, 10);

        ClassifyResult result = dirty.toResult();

        assertThat(result.category()).isEqualTo(TicketCategoryEnum.OTHER);
        assertThat(result.priority()).isEqualTo(TicketPriorityEnum.MEDIUM);
    }
}
