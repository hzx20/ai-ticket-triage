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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 分类缓存 key 生成测试
 *
 * <p>
 * key 算错是这类缓存里最隐蔽的 bug：不会抛异常、不会打错误日志，只是命中率永远是 0，
 * 表面上「缓存上线了」，实际上一次都没用上。所以这里把 key 的每一条性质都钉死。
 * </p>
 *
 * @author WorkBuddy
 * @since 1.1.0
 */
class ClassifyCacheKeyTest {

    @Test
    @DisplayName("相同内容生成相同的 key")
    void sameContentShouldProduceSameKey() {
        String first = ClassifyCache.buildKey(1L, "登不进去", "整个组都卡在加载页");
        String second = ClassifyCache.buildKey(1L, "登不进去", "整个组都卡在加载页");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("不同租户生成不同的 key")
    void differentTenantShouldProduceDifferentKey() {
        String tenantOne = ClassifyCache.buildKey(1L, "登不进去", "整个组都卡在加载页");
        String tenantTwo = ClassifyCache.buildKey(2L, "登不进去", "整个组都卡在加载页");

        assertThat(tenantOne).isNotEqualTo(tenantTwo);
        assertThat(tenantOne).startsWith("triage:cls:1:");
        assertThat(tenantTwo).startsWith("triage:cls:2:");
    }

    @Test
    @DisplayName("标题和正文的拼接边界不能串味")
    void titleAndContentBoundaryShouldNotCollide() {
        // 如果不加分隔符，这两组内容拼起来都是「ab」+「c」与「a」+「bc」以外的相同字符串，
        // 用 \u0001 分隔后哈希必然不同
        String first = ClassifyCache.buildKey(1L, "ab", "cde");
        String second = ClassifyCache.buildKey(1L, "a", "bcde");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("前后空白应被裁剪，避免同一内容因空格差异重复调用模型")
    void surroundingWhitespaceShouldBeTrimmed() {
        String trimmed = ClassifyCache.buildKey(1L, "登不进去", "停在加载页");
        String padded = ClassifyCache.buildKey(1L, "  登不进去 ", " 停在加载页  ");

        assertThat(trimmed).isEqualTo(padded);
    }

    @Test
    @DisplayName("超长正文只取前 2000 字参与哈希")
    void contentBeyondLimitShouldBeIgnored() {
        String prefix = "登录".repeat(1000);
        String first = ClassifyCache.buildKey(1L, "标题", prefix + "尾部甲");
        String second = ClassifyCache.buildKey(1L, "标题", prefix + "尾部乙");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("租户为空时按 0 处理，不抛空指针")
    void nullTenantShouldBeTreatedAsZero() {
        String nullTenant = ClassifyCache.buildKey(null, "标题", "内容");
        String zeroTenant = ClassifyCache.buildKey(0L, "标题", "内容");

        assertThat(nullTenant).isEqualTo(zeroTenant).startsWith("triage:cls:0:");
    }

    @Test
    @DisplayName("标题或正文为空时不抛异常，且 key 长度固定")
    void nullTitleOrContentShouldNotThrow() {
        String key = ClassifyCache.buildKey(1L, null, null);

        // 前缀 triage:cls:（11 字符）+ 租户（1 字符）+ 冒号（1 字符）+ 32 位哈希
        assertThat(key).hasSize(ClassifyCache.KEY_PREFIX.length() + 1 + 1 + 32);
    }
}
