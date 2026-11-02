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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;

/**
 * 工单分类器
 *
 * <p>
 * 调大模型判定分类和优先级，模型不可用时自动降级为关键词规则。
 * </p>
 *
 * <p>
 * 这里有意保留三处「不优雅」的设计，都是刻意的：
 * </p>
 * <ol>
 * <li>用 ObjectProvider 拿 ChatModel：没配 API Key 时应用仍然能启动，只是永远走兜底；</li>
 * <li>大模型调用整体包在 try-catch 里：分类是锦上添花，不能因为它挂了导致工单提交失败；</li>
 * <li>解析失败也走兜底：模型偶尔会返回带 markdown 代码块的 JSON，直接反序列化会炸。</li>
 * </ol>
 *
 * @author WorkBuddy
 * @since 1.0.0
 */
@Slf4j
@Component
public class TicketClassifier {

    /**
     * 占位 Key，与 application.yml 里的默认值保持一致
     */
    private static final String PLACEHOLDER_KEY = "sk-not-configured";

    private static final String SYSTEM_PROMPT = """
        你是企业内部的 IT 工单分类助手。你的任务是根据工单标题和内容，判断它属于哪个分类、紧急程度如何。

        分类只能从下面六个值里选一个，必须原样返回值本身：
        - account：账号与权限（登录失败、密码、验证码、账号被锁、权限不足、角色配置）
        - fault：系统故障（页面报错、接口 500、无法访问、响应慢、功能点了没反应、服务异常）
        - feature：功能需求（希望新增功能、提出改进建议、能不能支持某个操作）
        - data：数据问题（数据错误、统计对不上、导入导出失败、数据丢失、报表异常）
        - consult：使用咨询（询问怎么操作、在哪里找某个功能、使用方法、流程说明）
        - other：以上都不属于

        优先级只能是 1、2、3 中的一个数字：
        - 3（高）：影响多人或核心业务，例如生产环境不可用、全员无法登录、数据丢失
        - 2（中）：影响个人或单个功能，有替代方案，例如某个页面报错、导出失败
        - 1（低）：不影响业务使用，例如使用咨询、功能建议、界面优化

        输出要求：
        1. 只输出一个 JSON 对象，不要加任何解释、不要用 markdown 代码块包裹
        2. JSON 只有三个字段：category（字符串）、priority（数字）、reason（不超过 60 字的中文理由）
        3. category 必须是上面六个值之一，priority 必须是 1、2、3 之一

        示例输出：
        {"category":"account","priority":3,"reason":"多人反馈无法登录，属于账号权限问题且影响范围大"}
        """;

    private final ObjectProvider<ChatModel> chatModelProvider;

    private final ObjectMapper objectMapper;

    @Value("${spring.ai.openai.api-key:" + PLACEHOLDER_KEY + "}")
    private String apiKey;

    private ChatClient chatClient;

    public TicketClassifier(ObjectProvider<ChatModel> chatModelProvider,
        ObjectMapper objectMapper) {
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    /**
     * 对工单做分类判定
     *
     * @param title   标题
     * @param content 内容
     * @return 分类结果，永不为 null
     */
    public ClassifyResult classify(String title, String content) {
        long start = System.currentTimeMillis();

        if (PLACEHOLDER_KEY.equals(apiKey) || apiKey.isBlank()) {
            log.debug("未配置 DEEPSEEK_API_KEY，工单分类直接走规则兜底");
            return ruleBased(title, content, "未配置模型密钥，使用关键词规则判定", start);
        }

        ChatModel chatModel = chatModelProvider.getIfAvailable();
        if (chatModel == null) {
            return ruleBased(title, content, "模型客户端未初始化，使用关键词规则判定", start);
        }

        try {
            if (chatClient == null) {
                // ChatClient 是轻量包装，构造一次复用即可，不必每次新建
                this.chatClient = ChatClient.builder(chatModel).build();
            }
            String raw = chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserPrompt(title, content))
                .call()
                .content();
            return parseModelOutput(raw, start);
        } catch (Exception e) {
            // 分类失败绝不能影响工单入库，所以这里吞掉异常只记日志
            log.warn("工单分类调用模型失败，降级为规则判定。title={}, 原因={}", title, e.getMessage());
            return ruleBased(title, content,
                "模型调用失败（" + e.getClass().getSimpleName() + "），使用关键词规则判定", start);
        }
    }

    /**
     * 构造用户提示词：把标题和内容分开，避免标题和正文粘连导致模型误判
     *
     * @param title   标题
     * @param content 内容
     * @return 组装好的用户提示词
     */
    private String buildUserPrompt(String title, String content) {
        String safeContent = content == null ? "" : content.trim();
        // 超长内容截断，控制 token 成本；工单前 1500 字足够判断分类
        if (safeContent.length() > 1500) {
            safeContent = safeContent.substring(0, 1500) + "...（内容过长已截断）";
        }
        return "标题：" + title + "\n内容：" + safeContent;
    }

    /**
     * 解析模型输出
     *
     * <p>
     * 模型有时会在 JSON 外面套 ```json ``` 代码块，所以先截取第一个 { 到最后一个 } 之间的内容。
     * </p>
     *
     * @param raw   模型原始输出
     * @param start 开始时间戳（毫秒）
     * @return 分类结果
     */
    private ClassifyResult parseModelOutput(String raw, long start) {
        int cost = (int) (System.currentTimeMillis() - start);
        if (raw == null || raw.isBlank()) {
            return ruleBased(null, null, "模型返回为空，使用关键词规则判定", start);
        }
        int from = raw.indexOf('{');
        int to = raw.lastIndexOf('}');
        if (from < 0 || to <= from) {
            log.warn("模型输出不是 JSON：{}", raw);
            return ruleBased(null, null, "模型输出格式异常，使用关键词规则判定", start);
        }
        try {
            JsonNode node = objectMapper.readTree(raw.substring(from, to + 1));
            String categoryText = node.path("category").asText("");
            int priorityValue = node.path("priority").asInt(2);
            String reason = node.path("reason").asText("模型未给出理由");
            return ClassifyResult.ofModel(TicketCategoryEnum.parse(categoryText), TicketPriorityEnum
                .parse(priorityValue), reason, cost);
        } catch (Exception e) {
            log.warn("模型输出解析失败：{}，原因={}", raw, e.getMessage());
            return ruleBased(null, null, "模型输出解析失败，使用关键词规则判定", start);
        }
    }

    /**
     * 关键词规则兜底
     *
     * <p>
     * 规则判断注定不如模型细，但它有两个模型给不了的东西：确定性和零成本。
     * 在模型限流、网络抖动、密钥失效的时候，工单至少还能被分到一个大致正确的筐里。
     * </p>
     *
     * @param title   标题
     * @param content 内容
     * @param reason  兜底原因说明
     * @param start   开始时间戳（毫秒）
     * @return 分类结果
     */
    private ClassifyResult ruleBased(String title, String content, String reason, long start) {
        String text =
            ((title == null ? "" : title) + " " + (content == null ? "" : content)).toLowerCase();
        int cost = (int) (System.currentTimeMillis() - start);

        TicketCategoryEnum category;
        if (containsAny(text, "登录", "密码", "验证码", "权限", "账号", "授权", "角色")) {
            category = TicketCategoryEnum.ACCOUNT;
        } else if (containsAny(text, "报错", "异常", "500", "502", "504", "打不开", "无法访问", "崩溃", "超时",
            "没反应")) {
            category = TicketCategoryEnum.FAULT;
        } else if (containsAny(text, "希望", "建议", "能否", "能不能", "新增", "支持一下", "优化")) {
            category = TicketCategoryEnum.FEATURE;
        } else if (containsAny(text, "数据", "导入", "导出", "统计", "报表", "对不上", "丢失", "重复")) {
            category = TicketCategoryEnum.DATA;
        } else if (containsAny(text, "怎么", "如何", "在哪", "教程", "说明", "流程")) {
            category = TicketCategoryEnum.CONSULT;
        } else {
            category = TicketCategoryEnum.OTHER;
        }

        TicketPriorityEnum priority =
            containsAny(text, "生产", "全员", "宕机", "紧急", "全部用户", "无法登录", "数据丢失")
                ? TicketPriorityEnum.HIGH
                : TicketPriorityEnum.MEDIUM;

        return ClassifyResult.ofRule(category, priority, reason, cost);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
