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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 分类准确率评测
 *
 * <p>
 * 这是一个「手动触发」的评测，不是普通单测：它会真的调 30 次模型，花钱、依赖网络、
 * 结果还有轻微随机性，所以不能进 CI 的默认流程。
 * </p>
 *
 * <p>
 * 运行方式（Git Bash）：
 * </p>
 * <pre>
 * cd backend/continew-plugin/continew-plugin-ticket
 * RUN_AI_EVAL=true DEEPSEEK_API_KEY=sk-xxx \
 *   ../../mvnw test -Dtest=TicketEvalRunner
 * </pre>
 *
 * <p>
 * 设计上刻意保留了「规则基线」这一列：只报模型准确率没有意义，必须和规则对照，
 * 才能说明花的这笔钱换来了什么。如果模型和规则的准确率差不多，那这个 AI 模块就不该存在。
 * </p>
 *
 * @author WorkBuddy
 * @since 1.1.0
 */
@EnabledIfEnvironmentVariable(named = "RUN_AI_EVAL", matches = "true")
class TicketEvalRunner {

    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com";

    private static final String DEFAULT_MODEL = "deepseek-flash";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("对照评测：规则基线 vs 大模型")
    void evaluateAccuracy() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("需要设置环境变量 DEEPSEEK_API_KEY").isNotBlank();

        List<EvalCase> cases = loadCases();
        assertThat(cases).as("评测集不能为空").isNotEmpty();

        TicketClassifier classifier = buildClassifier(apiKey);

        // 第一轮：只用关键词规则，作为基线
        int ruleCategoryHit = 0;
        int rulePriorityHit = 0;
        // 第二轮：走完整链路（模型优先，失败才兜底）
        int modelCategoryHit = 0;
        int modelPriorityHit = 0;
        int fallbackCount = 0;
        long totalCost = 0L;
        List<String> misses = new ArrayList<>();

        for (EvalCase evalCase : cases) {
            ClassifyResult ruleResult = classifier
                .ruleBased(evalCase.title(), evalCase.content(), "评测基线", System
                    .currentTimeMillis());
            if (evalCase.expectCategory().equals(ruleResult.category().getValue())) {
                ruleCategoryHit++;
            }
            if (evalCase.expectPriority().equals(ruleResult.priority().getValue())) {
                rulePriorityHit++;
            }

            ClassifyResult modelResult = classifier.classify(evalCase.title(), evalCase.content());
            totalCost += modelResult.costMs();
            if (modelResult.fallback()) {
                fallbackCount++;
            }
            boolean categoryOk =
                evalCase.expectCategory().equals(modelResult.category().getValue());
            boolean priorityOk =
                evalCase.expectPriority().equals(modelResult.priority().getValue());
            if (categoryOk) {
                modelCategoryHit++;
            }
            if (priorityOk) {
                modelPriorityHit++;
            }
            if (!categoryOk || !priorityOk) {
                misses.add(String.format("  未命中：%s | 期望 %s/%d，实际 %s/%d（%s）", evalCase
                    .title(), evalCase.expectCategory(), evalCase.expectPriority(),
                    modelResult
                        .category()
                        .getValue(),
                    modelResult.priority().getValue(), modelResult.reason()));
            }
        }

        int total = cases.size();
        printReport(total, ruleCategoryHit, rulePriorityHit, modelCategoryHit, modelPriorityHit,
            fallbackCount, totalCost, misses);

        // 这条断言是评测的意义所在：模型必须明显优于规则，否则这个 AI 模块没有存在价值
        assertThat(modelCategoryHit)
            .as("模型分类命中数应不低于是规则基线的 1.2 倍")
            .isGreaterThanOrEqualTo((int) Math.ceil(ruleCategoryHit * 1.2));
    }

    /**
     * 构造一个不依赖 Spring 容器的分类器
     *
     * <p>
     * 用 StaticListableBeanFactory 手工注册一个 ChatModel 再取 ObjectProvider，
     * 比手写 ObjectProvider 的实现类干净，也不用为了评测去启动整个应用上下文。
     * </p>
     *
     * @param apiKey 模型密钥
     * @return 分类器
     */
    private TicketClassifier buildClassifier(String apiKey) {
        String baseUrl = System.getenv().getOrDefault("DEEPSEEK_BASE_URL", DEFAULT_BASE_URL);
        String model = System.getenv().getOrDefault("DEEPSEEK_MODEL", DEFAULT_MODEL);

        OpenAiApi openAiApi = OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build();
        ChatModel chatModel = OpenAiChatModel.builder()
            .openAiApi(openAiApi)
            .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model(model)
                .temperature(0.1)
                .build())
            .build();

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("chatModel", chatModel);
        ObjectProvider<ChatModel> provider = beanFactory.getBeanProvider(ChatModel.class);

        TicketClassifier classifier = new TicketClassifier(provider, objectMapper);
        // apiKey 平时由 @Value 注入，这里没有 Spring 容器，手工塞进去让 classify 走模型分支
        ReflectionTestUtils.setField(classifier, "apiKey", apiKey);
        return classifier;
    }

    private List<EvalCase> loadCases() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/ticket-eval-set.jsonl")) {
            assertThat(in).as("找不到评测集 ticket-eval-set.jsonl").isNotNull();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in,
                StandardCharsets.UTF_8))) {
                List<EvalCase> cases = new ArrayList<>();
                for (String line : reader.lines().toList()) {
                    if (!line.isBlank()) {
                        cases.add(objectMapper.readValue(line, EvalCase.class));
                    }
                }
                return cases;
            }
        }
    }

    private void printReport(int total,
        int ruleCategoryHit,
        int rulePriorityHit,
        int modelCategoryHit,
        int modelPriorityHit,
        int fallbackCount,
        long totalCost,
        List<String> misses) {
        System.out.println();
        System.out.println("=== AI 工单分类准确率评测 ===");
        System.out.printf("样本数：%d%n", total);
        System.out.println();
        System.out.println("【规则基线】");
        System.out.printf("  分类准确率：%d/%d = %.1f%%%n", ruleCategoryHit, total, pct(ruleCategoryHit,
            total));
        System.out.printf("  优先级准确率：%d/%d = %.1f%%%n", rulePriorityHit, total, pct(
            rulePriorityHit, total));
        System.out.println();
        System.out.println("【大模型】");
        System.out.printf("  分类准确率：%d/%d = %.1f%%%n", modelCategoryHit, total, pct(
            modelCategoryHit, total));
        System.out.printf("  优先级准确率：%d/%d = %.1f%%%n", modelPriorityHit, total, pct(
            modelPriorityHit, total));
        System.out.printf("  兜底条数：%d（兜底率 %.1f%%）%n", fallbackCount, pct(fallbackCount, total));
        System.out.printf("  平均耗时：%.0f ms/条%n", (double) totalCost / total);
        System.out.println();
        if (!misses.isEmpty()) {
            System.out.println("【未命中明细】");
            misses.forEach(System.out::println);
        }
        System.out.println("=== 评测结束 ===");
    }

    private double pct(int part, int total) {
        return total == 0 ? 0D : part * 100D / total;
    }

    /**
     * 一条评测样本
     *
     * @param title          工单标题
     * @param content        工单正文
     * @param expectCategory 人工标注的期望分类
     * @param expectPriority 人工标注的期望优先级
     */
    record EvalCase(String title, String content, String expectCategory, Integer expectPriority) {
    }
}
