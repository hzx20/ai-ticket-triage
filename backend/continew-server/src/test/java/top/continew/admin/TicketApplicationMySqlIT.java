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

package top.continew.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.common.constant.CacheConstants;
import top.continew.admin.common.context.UserContext;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.model.req.TicketReq;
import top.continew.admin.ticket.service.TicketService;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRunner;
import top.continew.starter.cache.redisson.util.RedisUtils;
import top.continew.starter.extension.tenant.context.TenantContext;
import top.continew.starter.extension.tenant.context.TenantContextHolder;
import top.continew.starter.extension.tenant.util.TenantUtils;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Cipher;

/**
 * 显式启用时在隔离 MySQL 和 Redis 上验证真实 Spring 应用与工单链路。
 *
 * @author hzx20
 * @since 4.2.0
 */
@EnabledIfEnvironmentVariable(named = "TICKET_FULL_STACK_TEST", matches = "true")
@AutoConfigureMockMvc
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    properties = {"continew.ticket.job.poll-ms=3600000",
        "spring.ai.openai.api-key=sk-not-configured"})
class TicketApplicationMySqlIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TicketService tickets;

    @Autowired
    private TicketMapper mapper;

    @Autowired
    private TicketClassifyJobRunner runner;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SpringLiquibase liquibase;

    @Autowired
    private MockMvc http;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Environment environment;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String url = System.getenv("TICKET_MYSQL_TEST_URL");
        if (url == null || !url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"
            + "codex_ticket_test_[a-z0-9_]+(\\?.*)?")) {
            throw new IllegalArgumentException("只允许本机隔离测试数据库");
        }
        String separator = url.contains("?") ? "&" : "?";
        registry.add("spring.datasource.url", () -> url.replace("jdbc:mysql:", "jdbc:p6spy:mysql:")
            + separator + "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        registry.add("spring.datasource.username", () -> System.getenv("TICKET_MYSQL_TEST_USER"));
        registry.add("spring.datasource.password", () -> Objects
            .toString(System.getenv("TICKET_MYSQL_TEST_PASSWORD"), ""));
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> System.getenv("TICKET_REDIS_TEST_PORT"));
        registry.add("spring.data.redis.password", () -> "");
    }

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
        UserContextHolder.clearContext();
    }

    @Test
    void migrationTransactionsTenantRecoveryAndRevision() throws Exception {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
            .startsWith("codex_ticket_test_");
        Long changes = jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG "
            + "WHERE FILENAME LIKE '%plugin_ticket_revision.sql' "
            + "OR FILENAME LIKE '%plugin_ticket_job.sql'", Long.class)).isEqualTo(3L);
        liquibase.afterPropertiesSet();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM DATABASECHANGELOG", Long.class))
            .isEqualTo(changes);

        long tenantId = createTenant();
        UserContext user = new UserContext();
        user.setId(1L);
        user.setTenantId(tenantId);
        UserContextHolder.setContext(user, false);
        setTenant(tenantId);
        String title = "e2e-" + UUID.randomUUID();
        TicketReq rollback = request(title + "-rollback", "登录失败");
        Long rolledBackId = new TransactionTemplate(transactionManager).execute(status -> {
            Long created = tickets.create(rollback);
            status.setRollbackOnly();
            return created;
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket WHERE title = ?", Long.class,
            rollback.getTitle())).isZero();
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM ticket_classify_job WHERE ticket_id = ?",
                Long.class, rolledBackId))
            .isZero();

        Long id = tickets.create(request(title, "登录失败"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_classify_job "
            + "WHERE tenant_id = ? AND ticket_id = ? AND status = 'PENDING'", Long.class, tenantId,
            id)).isEqualTo(1L);
        TicketReq edited = request(title + "-edited", "页面报错");
        edited.setRevision(0L);
        tickets.update(edited, id);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_classify_job "
            + "WHERE tenant_id = ? AND ticket_id = ?", Long.class, tenantId, id)).isEqualTo(2L);
        jdbc.update("UPDATE ticket_classify_job SET status = 'RUNNING', lease_token = 'expired', "
            + "lease_until = TIMESTAMPADD(SECOND, -1, CURRENT_TIMESTAMP(3)) "
            + "WHERE tenant_id = ? AND ticket_id = ? AND expected_revision = 1", tenantId, id);
        TenantUtils.execute(0L, () -> assertThat(mapper.selectById(id)).isNull());
        assertThat(tickets.classificationStatuses(List.of(id))).containsKey(id);
        TenantUtils.execute(0L,
            () -> assertThat(tickets.classificationStatuses(List.of(id))).isEmpty());
        runner.dispatch();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && jdbc.queryForObject("SELECT COUNT(*) FROM "
            + "ticket_classify_job WHERE ticket_id = ? AND status = 'COMPLETED'", Long.class,
            id) == 0L) {
            Thread.sleep(100);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_classify_job "
            + "WHERE tenant_id = ? AND ticket_id = ? AND expected_revision = 0 "
            + "AND status = 'SUPERSEDED'", Long.class, tenantId, id)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_classify_job "
            + "WHERE tenant_id = ? AND ticket_id = ? AND expected_revision = 1 "
            + "AND status = 'COMPLETED'", Long.class, tenantId, id)).isEqualTo(1L);
        TicketDO finished = mapper.selectById(id);
        assertThat(finished.getRevision()).isEqualTo(2L);
        assertThat(finished.getCategory()).isEqualTo("fault");
        assertThat(finished.getUpdateUser()).isNull();
    }

    @Test
    void reusedWorkerDoesNotLeakTenantOrUserContext() throws Exception {
        long firstTenant = createTenant();
        long secondTenant = createTenant();
        UserContext user = new UserContext();
        user.setId(1L);
        UserContextHolder.setContext(user, false);
        setTenant(firstTenant);
        Long first = tickets.create(request("worker-a-" + UUID.randomUUID(), "登录失败"));
        setTenant(secondTenant);
        Long second = tickets.create(request("worker-b-" + UUID.randomUUID(), "页面报错"));
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) ReflectionTestUtils
            .getField(runner, "executor");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        try {
            runner.dispatch();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline && jdbc.queryForObject("SELECT COUNT(*) FROM "
                + "ticket_classify_job WHERE ticket_id IN (?, ?) AND status = 'COMPLETED'",
                Long.class, first, second) != 2L) {
                Thread.sleep(100);
            }
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ticket_classify_job "
                + "WHERE ticket_id IN (?, ?) AND status = 'COMPLETED'", Long.class, first, second))
                .isEqualTo(2L);
            TenantUtils.execute(firstTenant, () -> {
                assertThat(mapper.selectById(first).getCategory()).isEqualTo("account");
                assertThat(mapper.selectById(first).getUpdateUser()).isNull();
                assertThat(mapper.selectById(second)).isNull();
            });
            TenantUtils.execute(secondTenant, () -> {
                assertThat(mapper.selectById(second).getCategory()).isEqualTo("fault");
                assertThat(mapper.selectById(second).getUpdateUser()).isNull();
                assertThat(mapper.selectById(first)).isNull();
            });
        } finally {
            executor.setMaxPoolSize(4);
            executor.setCorePoolSize(2);
        }
    }

    @Test
    void anonymousTicketRequestIsRejected() throws Exception {
        var anonymous = http.perform(get("/ticket/manage")).andReturn().getResponse();
        assertThat(anonymous.getStatus()).isEqualTo(401);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TICKET_HTTP_TEST", matches = "true")
    void httpAuthenticationCreateStatusConflictAndFallback() throws Exception {
        String oldPassword = jdbc.queryForObject("SELECT password FROM sys_user WHERE id = 1",
            String.class);
        String password = "T" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("UPDATE sys_user SET password = ? WHERE id = 1",
            passwordEncoder.encode(password));
        String captchaId = UUID.randomUUID().toString();
        RedisUtils.set(CacheConstants.CAPTCHA_KEY_PREFIX + captchaId, "ABCD",
            Duration.ofMinutes(2));
        String createdId = null;
        try {
            byte[] key = Base64.getDecoder().decode(environment
                .getRequiredProperty("continew-starter.encrypt.field.public-key"));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(key)));
            String encrypted = Base64.getEncoder()
                .encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
            String loginBody = json.writeValueAsString(Map.of("clientId",
                "ef51c9a3e9046c4f2ea45142c8a8344a", "authType", "ACCOUNT", "username", "admin",
                "password", encrypted, "captcha", "ABCD", "uuid", captchaId));
            JsonNode login = json.readTree(http.perform(post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON).content(loginBody)).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(login.path("success").asBoolean()).as("登录失败：%s", login.path("msg")).isTrue();
            String auth = "Bearer " + login.path("data").path("accessToken").asText();
            String title = "http-" + UUID.randomUUID();
            String body =
                json.writeValueAsString(Map.of("title", title, "content", "页面报错"));
            JsonNode created = json.readTree(http.perform(post("/ticket/manage")
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(created.path("success").asBoolean()).isTrue();
            String id = created.path("data").path("id").asText();
            assertThat(id).isNotBlank();
            createdId = id;
            JsonNode states = json.readTree(http.perform(get("/ticket/manage/classification-status")
                .header("Authorization", auth).param("ids", id)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
            assertThat(states.path("success").asBoolean()).isTrue();
            assertThat(states.path("data").path(id).asText()).isEqualTo("PENDING");
            String update = json.writeValueAsString(Map.of("title", title + "-edited",
                "content", "页面报错", "revision", 0));
            JsonNode firstUpdate = json.readTree(http.perform(put("/ticket/manage/{id}", id)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(update))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(firstUpdate.path("success").asBoolean()).isTrue();
            JsonNode staleUpdate = json.readTree(http.perform(put("/ticket/manage/{id}", id)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(update))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(staleUpdate.path("success").asBoolean()).isFalse();
            assertThat(staleUpdate.toString()).contains("刷新后重试");
            JsonNode classify = json.readTree(http.perform(post("/ticket/manage/{id}/classify", id)
                .header("Authorization", auth)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
            assertThat(classify.path("success").asBoolean()).isTrue();
            JsonNode detail = json.readTree(http.perform(get("/ticket/manage/{id}", id)
                .header("Authorization", auth)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
            assertThat(detail.path("data").path("aiSource").asText()).isEqualTo("RULE");
            JsonNode fields = detail.path("data");
            String fullForm = json.writeValueAsString(Map.of("title", title + "-edited", "content",
                "页面报错", "category", fields.path("category").asText(), "priority", fields
                    .path("priority").asInt(),
                "status", 2, "revision", fields.path("revision")
                    .asLong()));
            JsonNode fullUpdate = json.readTree(http.perform(put("/ticket/manage/{id}", id)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(fullForm)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
            assertThat(fullUpdate.path("success").asBoolean()).isTrue();
            JsonNode afterFull = json.readTree(http.perform(get("/ticket/manage/{id}", id)
                .header("Authorization", auth)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
            assertThat(afterFull.path("aiSource").asText()).isEqualTo("RULE");
            String changedText =
                json.writeValueAsString(Map.of("title", title + "-changed", "content",
                    "新的问题", "category", afterFull.path("category").asText(), "priority", afterFull
                        .path("priority").asInt(),
                    "revision", afterFull.path("revision").asLong()));
            JsonNode invalidated = json.readTree(http.perform(put("/ticket/manage/{id}", id)
                .header("Authorization", auth).contentType(MediaType.APPLICATION_JSON)
                .content(changedText)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
            assertThat(invalidated.path("success").asBoolean()).isTrue();
            JsonNode afterTextChange = json.readTree(http.perform(get("/ticket/manage/{id}", id)
                .header("Authorization", auth)).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).path("data");
            assertThat(afterTextChange.path("aiSource").isNull()
                || afterTextChange.path("aiSource").isMissingNode()).isTrue();
            assertThat(afterTextChange.path("category").isNull()
                || afterTextChange.path("category").isMissingNode()).isTrue();
            JsonNode newState = json.readTree(http
                .perform(get("/ticket/manage/classification-status")
                    .header("Authorization", auth).param("ids", id))
                .andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
            assertThat(newState.path("data").path(id).asText()).isEqualTo("PENDING");
        } finally {
            if (createdId != null) {
                jdbc.update("DELETE FROM ticket_classify_job WHERE ticket_id = ? AND tenant_id = 0",
                    Long.parseLong(createdId));
                jdbc.update("DELETE FROM ticket WHERE id = ? AND tenant_id = 0",
                    Long.parseLong(createdId));
            }
            jdbc.update("UPDATE sys_user SET password = ? WHERE id = 1", oldPassword);
            RedisUtils.delete(CacheConstants.CAPTCHA_KEY_PREFIX + captchaId);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "TICKET_HTTP_TEST", matches = "true")
    void limitedRoleCannotReadTicketsOrClassificationStatus() throws Exception {
        long userId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 800000000000000L)
            + 100000000000000L;
        long roleId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 800000000000000L)
            + 100000000000000L;
        long ticketId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 800000000000000L)
            + 100000000000000L;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String username = "e2e_" + suffix;
        String password = "T" + UUID.randomUUID().toString().replace("-", "");
        String captchaId = UUID.randomUUID().toString();
        String jobId = UUID.randomUUID().toString();
        boolean roleCreated = false;
        boolean userCreated = false;
        boolean ticketCreated = false;
        boolean jobCreated = false;
        String auth = null;
        Cookie[] cookies = new Cookie[0];
        try {
            jdbc.update(
                "INSERT INTO sys_role (id, name, code, data_scope, create_user, create_time, "
                    + "tenant_id) VALUES (?, ?, ?, 4, 1, CURRENT_TIMESTAMP, 0)",
                roleId,
                "limited_" + suffix, "limited_" + suffix);
            roleCreated = true;
            jdbc.update("INSERT INTO sys_user (id, username, nickname, password, dept_id, status, "
                + "pwd_reset_time, create_user, create_time, tenant_id) "
                + "VALUES (?, ?, ?, ?, 1, 1, CURRENT_TIMESTAMP, 1, CURRENT_TIMESTAMP, 0)",
                userId, username, username, passwordEncoder.encode(password));
            userCreated = true;
            jdbc.update("INSERT INTO sys_user_role (user_id, role_id, tenant_id) VALUES (?, ?, 0)",
                userId, roleId);
            jdbc.update("INSERT INTO ticket (id, tenant_id, title, content, status, create_user, "
                + "create_time, revision) VALUES (?, 0, ?, ?, 1, 1, CURRENT_TIMESTAMP, 0)",
                ticketId, "permission-" + suffix, "test");
            ticketCreated = true;
            jdbc.update("INSERT INTO ticket_classify_job (id, tenant_id, ticket_id, "
                + "expected_revision, status, attempts, next_run_at, created_at, updated_at) "
                + "VALUES (?, 0, ?, 0, 'PENDING', 0, TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP(3)), "
                + "CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))", jobId, ticketId);
            jobCreated = true;
            RedisUtils.set(CacheConstants.CAPTCHA_KEY_PREFIX + captchaId, "ABCD",
                Duration.ofMinutes(2));
            byte[] key = Base64.getDecoder().decode(environment
                .getRequiredProperty("continew-starter.encrypt.field.public-key"));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(key)));
            String encrypted = Base64.getEncoder()
                .encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)));
            String loginBody = json.writeValueAsString(Map.of("clientId",
                "ef51c9a3e9046c4f2ea45142c8a8344a", "authType", "ACCOUNT", "username", username,
                "password", encrypted, "captcha", "ABCD", "uuid", captchaId));
            var loginResponse =
                http.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON)
                    .content(loginBody)).andReturn().getResponse();
            JsonNode login =
                json.readTree(loginResponse.getContentAsString(StandardCharsets.UTF_8));
            assertThat(login.path("success").asBoolean()).as("低权限用户登录").isTrue();
            auth = "Bearer " + login.path("data").path("accessToken").asText();
            cookies = loginResponse.getCookies();

            var statusResponse = http.perform(get("/ticket/manage/classification-status")
                .header("Authorization", auth).param("ids", String.valueOf(ticketId)))
                .andReturn().getResponse();
            assertThat(statusResponse.getStatus()).isEqualTo(403);
            JsonNode body =
                json.readTree(statusResponse.getContentAsString(StandardCharsets.UTF_8));
            assertThat(body.path("success").asBoolean()).isFalse();
            assertThat(body.path("data").isNull()).isTrue();
            assertThat(http.perform(get("/ticket/manage").header("Authorization", auth))
                .andReturn().getResponse().getStatus()).isEqualTo(403);
            assertThat(http.perform(get("/ticket/manage/{id}", ticketId)
                .header("Authorization", auth)).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        } finally {
            try {
                if (auth != null) {
                    http.perform(post("/auth/logout").header("Authorization", auth)
                        .header("Origin", "http://localhost:5173").cookie(cookies));
                }
            } finally {
                RedisUtils.delete(CacheConstants.CAPTCHA_KEY_PREFIX + captchaId);
                if (jobCreated) {
                    jdbc.update("DELETE FROM ticket_classify_job WHERE id = ? AND tenant_id = 0",
                        jobId);
                }
                if (ticketCreated) {
                    jdbc.update("DELETE FROM ticket WHERE id = ? AND tenant_id = 0", ticketId);
                }
                if (userCreated) {
                    jdbc.update("DELETE FROM sys_user_role WHERE user_id = ? AND role_id = ? "
                        + "AND tenant_id = 0", userId, roleId);
                    jdbc.update("DELETE FROM sys_log WHERE create_user = ?", userId);
                    jdbc.update("DELETE FROM sys_user WHERE id = ? AND tenant_id = 0", userId);
                }
                if (roleCreated) {
                    jdbc.update("DELETE FROM sys_role WHERE id = ? AND tenant_id = 0", roleId);
                }
            }
        }
    }

    private static TicketReq request(String title, String content) {
        TicketReq req = new TicketReq();
        req.setTitle(title);
        req.setContent(content);
        return req;
    }

    private long createTenant() {
        long tenantId = Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 900000000L)
            + 100000000L;
        jdbc.update("INSERT INTO tenant_package (id, name, status, create_user, create_time) "
            + "VALUES (?, ?, 1, 1, CURRENT_TIMESTAMP)", tenantId, "e2e-package");
        jdbc.update(
            "INSERT INTO tenant (id, name, code, status, package_id, create_user, create_time) "
                + "VALUES (?, ?, ?, 1, ?, 1, CURRENT_TIMESTAMP)",
            tenantId, "e2e-tenant",
            "e2e-" + tenantId, tenantId);
        return tenantId;
    }

    private static void setTenant(long id) {
        TenantContext context = new TenantContext();
        context.setTenantId(id);
        TenantContextHolder.setContext(context);
        TenantContextHolder.setIgnore(false);
    }
}
