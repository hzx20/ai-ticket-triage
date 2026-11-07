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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 内部分类任务存储。跨租户扫描只返回任务标识与租户标识，其他操作均显式限制租户。
 *
 * @author hzx20
 * @since 1.1.0
 */
@Repository
class TicketClassifyJobRepository {

    private static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;

    TicketClassifyJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 在创建工单的事务中持久化待处理任务。
    void enqueue(long tenantId, long ticketId, long revision) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("分类任务必须与工单写入处于同一事务");
        }
        jdbc.update("INSERT INTO ticket_classify_job "
            + "(id, tenant_id, ticket_id, expected_revision, status, attempts, next_run_at, "
            + "created_at, updated_at) VALUES (?, ?, ?, ?, 'PENDING', 0, "
            + "CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))",
            UUID.randomUUID().toString(), tenantId, ticketId, revision);
    }

    // 仅供调度器读取待认领任务标识，不返回任何工单内容。
    List<JobRef> ready(int limit) {
        return jdbc.query("SELECT id, tenant_id "
            + "FROM ticket_classify_job WHERE attempts < ? AND "
            + "((status = 'PENDING' AND next_run_at <= CURRENT_TIMESTAMP(3)) OR "
            + "(status = 'RUNNING' AND lease_until <= CURRENT_TIMESTAMP(3))) "
            + "ORDER BY created_at LIMIT ?",
            (rs, row) -> new JobRef(rs.getString("id"), rs
                .getLong("tenant_id")),
            MAX_ATTEMPTS, limit);
    }

    // 原子认领；token 限制后续完成和失败更新只能由有效持有者执行。
    Claim claim(JobRef job, long leaseSeconds) {
        String token = UUID.randomUUID().toString();
        LocalDateTime expires = databaseNow().plusSeconds(leaseSeconds);
        int changed = jdbc.update("UPDATE ticket_classify_job SET status = 'RUNNING', "
            + "lease_token = ?, lease_until = ?, attempts = attempts + 1, "
            + "updated_at = CURRENT_TIMESTAMP(3) WHERE id = ? AND tenant_id = ? AND attempts < ? AND "
            + "((status = 'PENDING' AND next_run_at <= CURRENT_TIMESTAMP(3)) OR "
            + "(status = 'RUNNING' AND lease_until <= CURRENT_TIMESTAMP(3)))",
            token, Timestamp.valueOf(expires), job.id(), job.tenantId(), MAX_ATTEMPTS);
        if (changed != 1) {
            return null;
        }
        List<Claim> rows = jdbc.query(
            "SELECT ticket_id, expected_revision, attempts FROM ticket_classify_job "
                + "WHERE id = ? AND tenant_id = ? AND status = 'RUNNING' AND lease_token = ? "
                + "AND lease_until > CURRENT_TIMESTAMP(3)",
            (rs, row) -> new Claim(job, token, rs
                .getLong("ticket_id"), rs.getLong("expected_revision"), rs.getInt("attempts")),
            job.id(), job.tenantId(),
            token);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // 必须在事务中调用；锁住租约行直至工单结果与任务状态一起提交。
    boolean lockValid(JobRef job, String token) {
        return !jdbc.query("SELECT id FROM ticket_classify_job WHERE id = ? AND tenant_id = ? "
            + "AND status = 'RUNNING' AND lease_token = ? AND lease_until > CURRENT_TIMESTAMP(3) "
            + "FOR UPDATE", (rs, row) -> rs.getString(1), job.id(), job.tenantId(), token)
            .isEmpty();
    }

    // 在持有有效任务行锁的同一个事务内调用。
    void finish(JobRef job, String token, boolean written) {
        int updated = jdbc.update("UPDATE ticket_classify_job SET status = ?, lease_token = NULL, "
            + "lease_until = NULL, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = ? AND tenant_id = ? AND status = 'RUNNING' AND lease_token = ?",
            written ? "COMPLETED" : "SUPERSEDED", job.id(), job.tenantId(), token);
        if (updated != 1) {
            throw new IllegalStateException("任务所有权已变更");
        }
    }

    // 异常后退避重试；过期或已转移的租约不可重排。
    void retry(Claim claim) {
        JobRef job = claim.job();
        long delay = Math.min(300L, 5L << Math.min(6, Math.max(0, claim.attempts() - 1)));
        LocalDateTime retryAt = databaseNow().plusSeconds(delay);
        jdbc.update("UPDATE ticket_classify_job SET status = ?, next_run_at = ?, "
            + "lease_token = NULL, lease_until = NULL, updated_at = CURRENT_TIMESTAMP(3) "
            + "WHERE id = ? AND tenant_id = ? AND status = 'RUNNING' AND lease_token = ? "
            + "AND lease_until > CURRENT_TIMESTAMP(3)",
            claim.attempts() >= MAX_ATTEMPTS ? "FAILED" : "PENDING", Timestamp.valueOf(retryAt),
            job.id(),
            job
                .tenantId(),
            claim.token());
    }

    // 最后一次执行期间进程退出时，租约到期后也要进入可见的失败状态。
    int failExhausted() {
        return jdbc.update("UPDATE ticket_classify_job SET status = 'FAILED', lease_token = NULL, "
            + "lease_until = NULL, updated_at = CURRENT_TIMESTAMP(3) WHERE status = 'RUNNING' "
            + "AND attempts >= ? AND lease_until <= CURRENT_TIMESTAMP(3)", MAX_ATTEMPTS);
    }

    // 工单可见性须由调用方先过滤；此处只查询指定租户内这些工单的任务状态。
    Map<Long, String> statuses(long tenantId, Map<Long, Long> revisions) {
        if (revisions.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", Collections.nCopies(revisions.size(), "?"));
        List<Object> parameters = new ArrayList<>();
        parameters.add(tenantId);
        parameters.addAll(revisions.keySet());
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.query("SELECT ticket_id, expected_revision, status FROM ticket_classify_job "
            + "WHERE tenant_id = ? AND ticket_id IN (" + placeholders + ") "
            + "ORDER BY created_at DESC, id DESC", rs -> {
                long ticketId = rs.getLong("ticket_id");
                Long revision = revisions.get(ticketId);
                if (revision != null && revision == rs.getLong("expected_revision")) {
                    result.putIfAbsent(ticketId, rs.getString("status"));
                }
            }, parameters.toArray());
        return result;
    }

    private LocalDateTime databaseNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP(3)", Timestamp.class);
        if (now == null) {
            throw new IllegalStateException("无法读取数据库时间");
        }
        return now.toLocalDateTime();
    }

    /**
     * 任务调度标识。
     *
     * @param id 任务 ID
     * @param tenantId 租户 ID
     */
    record JobRef(String id, long tenantId) {
    }

    /**
     * 已认领任务。
     *
     * @param job 任务
     * @param token 租约令牌
     * @param ticketId 工单 ID
     * @param revision 工单版本
     * @param attempts 已认领次数
     */
    record Claim(JobRef job, String token, long ticketId, long revision, int attempts) {
    }
}
