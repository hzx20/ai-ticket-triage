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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;

/**
 * 分类器测试
 *
 * <p>
 * 覆盖两块不依赖模型的逻辑：关键词规则兜底、模型输出解析。这两块恰好是最容易出错的地方——
 * 规则写错会导致降级后分类全错，解析写错会让一条正常的模型输出被判成兜底。
 * </p>
 *
 * <p>
 * 不在这里调真实模型：模型输出有随机性，把它写进单测等于让 CI 随机失败。
 * 需要验证模型效果的部分放在 {@link TicketEvalRunner}，手动触发。
 * </p>
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifierTest {

    private TicketClassifier classifier;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // apiKey 未注入（为 null）时分类器直接走规则分支，不会真的去拿 ChatModel，
        // 所以这里给一个返回 null 的 ObjectProvider 就够了
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        classifier = new TicketClassifier(provider, new ObjectMapper());
    }

    @Test
    @DisplayName("六类关键词规则各自命中正确的分类")
    void ruleShouldMapEachCategory() {
        assertThat(rule("登录报错", "密码输对了还是进不去").category())
            .isEqualTo(TicketCategoryEnum.ACCOUNT);
        assertThat(rule("页面打不开", "一直转圈然后 500").category())
            .isEqualTo(TicketCategoryEnum.FAULT);
        assertThat(rule("能不能加个功能", "希望支持批量导出").category())
            .isEqualTo(TicketCategoryEnum.FEATURE);
        assertThat(rule("报表对不上", "上周的数据少了几条").category())
            .isEqualTo(TicketCategoryEnum.DATA);
        assertThat(rule("在哪里配置", "这个流程怎么走").category())
            .isEqualTo(TicketCategoryEnum.CONSULT);
        assertThat(rule("随便说说", "没什么具体内容").category())
            .isEqualTo(TicketCategoryEnum.OTHER);
    }

    @Test
    @DisplayName("命中高危关键词时优先级升到高")
    void ruleShouldRaisePriorityOnCriticalKeyword() {
        assertThat(rule("生产环境宕机", "全员无法登录").priority())
            .isEqualTo(TicketPriorityEnum.HIGH);
        assertThat(rule("按钮颜色", "想换成蓝色").priority())
            .isEqualTo(TicketPriorityEnum.MEDIUM);
    }

    @Test
    @DisplayName("规则命中时来源标记为 RULE，且 fallback 为 true")
    void ruleResultShouldBeMarkedAsFallback() {
        ClassifyResult result = rule("登录报错", "密码进不去");

        assertThat(result.fallback()).isTrue();
        assertThat(result.source()).isEqualTo(ClassifySource.RULE);
    }

    @Test
    @DisplayName("标准 JSON 输出能被正确解析")
    void shouldParsePlainJson() {
        ClassifyResult result =
            parse("{\"category\":\"account\",\"priority\":3,\"reason\":\"多人无法登录\"}");

        assertThat(result.category()).isEqualTo(TicketCategoryEnum.ACCOUNT);
        assertThat(result.priority()).isEqualTo(TicketPriorityEnum.HIGH);
        assertThat(result.reason()).isEqualTo("多人无法登录");
        assertThat(result.fallback()).isFalse();
        assertThat(result.source()).isEqualTo(ClassifySource.MODEL);
    }

    @Test
    @DisplayName("被 markdown 代码块包裹的 JSON 也能解析")
    void shouldParseJsonWrappedInMarkdown() {
        String raw = """
            ```json
            {"category":"data","priority":2,"reason":"导出结果为空"}
            ```
            """;

        assertThat(parse(raw).category()).isEqualTo(TicketCategoryEnum.DATA);
    }

    @Test
    @DisplayName("缺失、类型错误或越界字段不能冒充可信模型结果")
    void invalidFieldsShouldUseRules() {
        String[] invalid = {
            "{}",
            "{\"category\":\"unknown-value\",\"priority\":2,\"reason\":\"x\"}",
            "{\"category\":\"fault\",\"priority\":9,\"reason\":\"x\"}",
            "{\"category\":\"fault\",\"priority\":\"3\",\"reason\":\"x\"}",
            "{\"category\":\"fault\",\"priority\":3,\"reason\":\"  \"}"
        };
        for (String raw : invalid) {
            ClassifyResult result = classifier.parseModelOutput(raw, "登录失败", "无法登录", System
                .currentTimeMillis());
            assertThat(result.fallback()).as(raw).isTrue();
            assertThat(result.category()).as(raw).isEqualTo(TicketCategoryEnum.ACCOUNT);
            assertThat(result.source()).as(raw).isEqualTo(ClassifySource.RULE);
        }
    }

    @Test
    @DisplayName("模型输出不是 JSON 时，降级必须带上原始标题正文重新判定")
    void nonJsonOutputShouldFallBackWithOriginalText() {
        // 回归测试：早期版本这里传的是 null, null，规则拿不到文本，
        // 结果所有解析失败的工单都被塞进「其他」，而日志上一切正常
        ClassifyResult result = classifier
            .parseModelOutput("抱歉，我无法完成这个任务", "登录失败", "整个组都卡在加载页", System
                .currentTimeMillis());

        assertThat(result.fallback()).isTrue();
        assertThat(result.category()).isEqualTo(TicketCategoryEnum.ACCOUNT);
    }

    @Test
    @DisplayName("模型返回空字符串时同样按原文降级")
    void blankOutputShouldFallBackWithOriginalText() {
        ClassifyResult result = classifier
            .parseModelOutput("   ", "报表数据对不上", "上周的统计少了几条", System.currentTimeMillis());

        assertThat(result.fallback()).isTrue();
        assertThat(result.category()).isEqualTo(TicketCategoryEnum.DATA);
    }

    @Test
    @DisplayName("模型客户端初始化抛异常时仍用工单原文回退")
    @SuppressWarnings("unchecked")
    void providerFailureShouldUseRules() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenThrow(new IllegalStateException("初始化失败"));
        TicketClassifier failed = new TicketClassifier(provider, new ObjectMapper());
        ReflectionTestUtils.setField(failed, "apiKey", "test-key");

        ClassifyResult result = failed.classify("登录失败", "整个组无法登录");

        assertThat(result.fallback()).isTrue();
        assertThat(result.category()).isEqualTo(TicketCategoryEnum.ACCOUNT);
    }

    private ClassifyResult rule(String title, String content) {
        return classifier.ruleBased(title, content, "测试", System.currentTimeMillis());
    }

    private ClassifyResult parse(String raw) {
        return classifier.parseModelOutput(raw, "标题", "内容", System.currentTimeMillis());
    }
}
