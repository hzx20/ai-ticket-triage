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

package top.continew.admin.ticket.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRepository.Claim;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRepository.JobRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 在显式指定的隔离 MySQL 测试库验证任务仓库的真实 SQL。
 * 只替换表名以使用本次随机创建的表，其余仓库和迁移 SQL 保持不变。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyJobMySqlTest {

    private JdbcTemplate jdbc;

    private TicketClassifyJobRepository jobs;

    private TransactionTemplate transaction;

    private String jobTable;

    private String ticketTable;

    private String backfill;

    private boolean ticketCreated;

    private boolean jobCreated;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("TICKET_MYSQL_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "未配置隔离 MySQL 测试库");
        assertThat(url).matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/"
            + "codex_ticket_test_[a-z0-9_]+(\\?.*)?");
        String user = System.getenv("TICKET_MYSQL_TEST_USER");
        String password = System.getenv("TICKET_MYSQL_TEST_PASSWORD");
        assertThat(user).isNotBlank();
        assertThat(password).isNotNull();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).startsWith("codex_ticket_test_");
        }
        String suffix = UUID.randomUUID().toString().replace("-", "");
        ticketTable = "test_ticket_" + suffix;
        jobTable = "test_job_" + suffix;
        jdbc = new IsolatedJdbcTemplate(dataSource, jobTable);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        jobs = new TicketClassifyJobRepository(jdbc);
        jdbc.execute("CREATE TABLE `" + ticketTable + "` (id BIGINT PRIMARY KEY, "
            + "tenant_id BIGINT NOT NULL, revision BIGINT NOT NULL DEFAULT 0, "
            + "deleted BIGINT NOT NULL DEFAULT 0, status INT NOT NULL, "
            + "category VARCHAR(30), priority INT, ai_source VARCHAR(16), ai_cost_ms INT) "
            + "ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        ticketCreated = true;
        String migration = Files.readString(Path.of("..", "..", "continew-server", "src", "main",
            "resources", "db", "changelog", "mysql", "plugin", "plugin_ticket_job.sql"));
        String sql = migration.lines().filter(line -> !line.stripLeading().startsWith("--"))
            .reduce("", (left, right) -> left + "\n" + right);
        String[] statements = sql.split(";");
        assertThat(statements).hasSize(2);
        assertThat(statements[0]).contains("`ticket_classify_job`");
        assertThat(statements[1]).contains("FROM ticket t");
        String create = statements[0].replace("ticket_classify_job", jobTable);
        backfill = statements[1].replace("ticket_classify_job", jobTable)
            .replace("FROM ticket t", "FROM `" + ticketTable + "` t");
        jdbc.execute(create);
        jobCreated = true;
    }

    @AfterEach
    void tearDown() {
        if (jdbc != null && jobCreated) {
            jdbc.execute("DROP TABLE `" + jobTable + "`");
        }
        if (jdbc != null && ticketCreated) {
            jdbc.execute("DROP TABLE `" + ticketTable + "`");
        }
    }

    @Test
    @DisplayName("并发认领同一任务时只有一个持有者")
    void concurrentClaimHasOneOwner() throws Exception {
        enqueue(11L, 101L, 7L);
        JobRef ref = jobs.ready(10).get(0);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Claim> first = executor.submit(() -> {
                start.await();
                return jobs.claim(ref, 60);
            });
            Future<Claim> second = executor.submit(() -> {
                start.await();
                return jobs.claim(ref, 60);
            });
            start.countDown();
            Claim a = first.get(10, TimeUnit.SECONDS);
            Claim b = second.get(10, TimeUnit.SECONDS);
            assertThat((a == null ? 0 : 1) + (b == null ? 0 : 1)).isEqualTo(1);
            Claim winner = a == null ? b : a;
            assertThat(winner.ticketId()).isEqualTo(101L);
            assertThat(winner.revision()).isEqualTo(7L);
            assertThat(
                jdbc.queryForObject("SELECT attempts FROM `" + jobTable + "`", Integer.class))
                .isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("过期租约被重新认领后旧 token 不能重试或完成")
    void expiredOwnerCannotChangeNewLease() {
        enqueue(11L, 101L, 7L);
        JobRef ref = jobs.ready(10).get(0);
        Claim old = jobs.claim(ref, 60);
        assertThat(old).isNotNull();
        jdbc.update("UPDATE `" + jobTable + "` SET lease_until = "
            + "CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id = ?", ref.id());
        Claim current = jobs.claim(ref, 60);
        assertThat(current).isNotNull();
        assertThat(current.token()).isNotEqualTo(old.token());
        Boolean oldValid = transaction.execute(status -> jobs.lockValid(ref, old.token()));
        assertThat(oldValid).isFalse();
        jobs.retry(old);
        assertThatThrownBy(
            () -> transaction.executeWithoutResult(status -> jobs.finish(ref, old.token(), true)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM `" + jobTable + "`", String.class))
            .isEqualTo(current.token());
        transaction.executeWithoutResult(status -> {
            assertThat(jobs.lockValid(ref, current.token())).isTrue();
            jobs.finish(ref, current.token(), true);
        });
        assertThat(jdbc.queryForObject("SELECT status FROM `" + jobTable + "`", String.class))
            .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("任务入队及结果与完成状态各自随事务回滚")
    void rollbackPreservesTicketAndJobState() {
        transaction.executeWithoutResult(status -> {
            jobs.enqueue(11L, 101L, 0L);
            status.setRollbackOnly();
        });
        assertThat(jobs.ready(10)).isEmpty();
        jdbc.update(
            "INSERT INTO `" + ticketTable + "` (id, tenant_id, status) VALUES (101, 11, 1)");
        enqueue(11L, 101L, 0L);
        JobRef ref = jobs.ready(10).get(0);
        Claim claim = jobs.claim(ref, 60);
        assertThat(claim).isNotNull();
        transaction.executeWithoutResult(status -> {
            assertThat(jobs.lockValid(ref, claim.token())).isTrue();
            jdbc.update("UPDATE `" + ticketTable + "` SET category = 'fault' WHERE id = 101");
            jobs.finish(ref, claim.token(), true);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT category FROM `" + ticketTable + "` WHERE id = 101",
            String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM `" + jobTable + "`", String.class))
            .isEqualTo("RUNNING");
    }

    @Test
    @DisplayName("跨租户任务标识不能认领或操作其他租户的租约")
    void tenantMismatchCannotOperateJob() {
        enqueue(11L, 101L, 0L);
        JobRef real = jobs.ready(10).get(0);
        JobRef wrongTenant = new JobRef(real.id(), 12L);
        assertThat(jobs.claim(wrongTenant, 60)).isNull();
        Claim claim = jobs.claim(real, 60);
        assertThat(claim).isNotNull();
        Boolean wrongTenantValid = transaction
            .execute(status -> jobs.lockValid(wrongTenant, claim.token()));
        assertThat(wrongTenantValid).isFalse();
        Claim wrongClaim = new Claim(wrongTenant, claim.token(), claim.ticketId(), claim.revision(),
            claim.attempts());
        jobs.retry(wrongClaim);
        assertThatThrownBy(() -> transaction
            .executeWithoutResult(status -> jobs.finish(wrongTenant, claim.token(), true)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT lease_token FROM `" + jobTable + "`", String.class))
            .isEqualTo(claim.token());
    }

    @Test
    @DisplayName("最后一次执行期间崩溃，租约到期后进入失败状态")
    void expiredExhaustedJobBecomesFailed() {
        enqueue(11L, 101L, 0L);
        JobRef ref = jobs.ready(10).get(0);
        jdbc.update("UPDATE `" + jobTable + "` SET attempts = 9 WHERE id = ?", ref.id());
        Claim claim = jobs.claim(ref, 60);
        assertThat(claim).isNotNull();
        assertThat(claim.attempts()).isEqualTo(10);
        assertThat(jobs.failExhausted()).isZero();
        jdbc.update("UPDATE `" + jobTable + "` SET lease_until = "
            + "CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE id = ?", ref.id());
        assertThat(jobs.failExhausted()).isEqualTo(1);
        assertThat(jobs.ready(10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM `" + jobTable + "`", String.class))
            .isEqualTo("FAILED");
        jobs.retry(claim);
        assertThat(jdbc.queryForObject("SELECT status FROM `" + jobTable + "`", String.class))
            .isEqualTo("FAILED");
    }

    @Test
    @DisplayName("状态查询只返回指定租户和当前工单版本")
    void statusesAreScopedToTenantAndRevision() {
        enqueue(11L, 101L, 1L);
        enqueue(11L, 101L, 2L);
        enqueue(12L, 102L, 2L);
        jdbc.update("UPDATE `" + jobTable + "` SET status = 'FAILED' "
            + "WHERE tenant_id = 11 AND ticket_id = 101 AND expected_revision = 1");
        assertThat(jobs.statuses(11L, Map.of(101L, 2L, 102L, 2L)))
            .containsOnlyKeys(101L).containsEntry(101L, "PENDING");
        assertThat(jobs.statuses(11L, Map.of(101L, 3L))).isEmpty();
        assertThat(jobs.statuses(12L, Map.of(102L, 2L))).containsEntry(102L, "PENDING");
    }

    @Test
    @DisplayName("历史回填只处理开放且没有分类结果的工单，重复运行不重复入队")
    void backfillIsSelectiveAndIdempotent() {
        jdbc.update("INSERT INTO `" + ticketTable + "` "
            + "(id, tenant_id, revision, deleted, status, category, priority, ai_source, ai_cost_ms) "
            + "VALUES (1, 11, 3, 0, 1, NULL, NULL, NULL, NULL), "
            + "(2, 11, 2, 0, 2, NULL, NULL, NULL, NULL), "
            + "(3, 11, 0, 0, 1, 'fault', 2, 'MODEL', 10), "
            + "(4, 11, 0, 0, 3, NULL, NULL, NULL, NULL), "
            + "(5, 11, 0, 5, 1, NULL, NULL, NULL, NULL)");
        enqueue(11L, 2L, 2L);
        jdbc.execute(backfill);
        jdbc.execute(backfill);
        List<Long> ticketIds = jdbc.queryForList("SELECT ticket_id FROM `" + jobTable
            + "` ORDER BY ticket_id", Long.class);
        assertThat(ticketIds).containsExactly(1L, 2L);
        assertThat(jdbc.queryForObject("SELECT expected_revision FROM `" + jobTable
            + "` WHERE ticket_id = 1", Long.class)).isEqualTo(3L);
    }

    private void enqueue(long tenantId, long ticketId, long revision) {
        transaction.executeWithoutResult(status -> jobs.enqueue(tenantId, ticketId, revision));
    }

    // JdbcTemplate 只将仓库 SQL 的表标识改为本次测试拥有的随机表。
    private static class IsolatedJdbcTemplate extends JdbcTemplate {

        private final String table;

        IsolatedJdbcTemplate(DriverManagerDataSource dataSource, String table) {
            super(dataSource);
            this.table = table;
        }

        @Override
        public int update(String sql, Object... args) {
            return super.update(sql.replace("ticket_classify_job", table), args);
        }

        @Override
        public void query(String sql, RowCallbackHandler handler, Object... args) {
            super.query(sql.replace("ticket_classify_job", table), handler, args);
        }

        @Override
        public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            return super.query(sql.replace("ticket_classify_job", table), rowMapper, args);
        }
    }
}
