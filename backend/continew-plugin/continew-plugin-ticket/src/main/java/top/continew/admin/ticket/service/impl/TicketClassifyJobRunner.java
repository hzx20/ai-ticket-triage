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

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.common.api.tenant.TenantApi;
import top.continew.admin.common.context.UserContextHolder;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRepository.Claim;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRepository.JobRef;
import top.continew.starter.extension.tenant.context.TenantContextHolder;
import top.continew.starter.extension.tenant.util.TenantUtils;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 持久分类任务扫描与恢复。模型调用不在数据库事务中，过期租约禁止写入结果。
 *
 * @author hzx20
 * @since 1.1.0
 */
@Slf4j
@Component
public class TicketClassifyJobRunner {

    private static final long LEASE_SECONDS = 120;

    private final TicketClassifyJobRepository jobs;

    private final TicketMapper tickets;

    private final TicketClassifyTask classification;

    private final ObjectProvider<TenantApi> tenantApi;

    private final TransactionTemplate transaction;

    private final ThreadPoolTaskExecutor executor;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public TicketClassifyJobRunner(TicketClassifyJobRepository jobs,
        TicketMapper tickets,
        TicketClassifyTask classification,
        ObjectProvider<TenantApi> tenantApi,
        PlatformTransactionManager transactionManager) {
        this.jobs = jobs;
        this.tickets = tickets;
        this.classification = classification;
        this.tenantApi = tenantApi;
        this.transaction = new TransactionTemplate(transactionManager);
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(4);
        pool.setQueueCapacity(64);
        pool.setThreadNamePrefix("ticket-job-");
        pool.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        pool.setWaitForTasksToCompleteOnShutdown(true);
        pool.setAwaitTerminationSeconds(30);
        pool.initialize();
        this.executor = pool;
    }

    /**
     * 定时扫描；线程池饱和时任务留在数据库，由后续扫描重试。
     */
    @Scheduled(fixedDelayString = "${continew.ticket.job.poll-ms:5000}")
    public void dispatch() {
        int exhausted = jobs.failExhausted();
        if (exhausted > 0) {
            log.warn("分类任务达到最大重试次数，等待人工处理。数量={}", exhausted);
        }
        for (JobRef ref : jobs.ready(32)) {
            if (!inFlight.add(ref.id())) {
                continue;
            }
            try {
                executor.execute(() -> {
                    Claim claim = null;
                    try {
                        // 工作线程不代表请求用户，避免继承线程创建时的审计身份。
                        UserContextHolder.clearContext();
                        TenantContextHolder.clear();
                        claim = jobs.claim(ref, LEASE_SECONDS);
                        if (claim == null) {
                            return;
                        }
                        if (TenantContextHolder.isTenantEnabled()) {
                            tenantApi.getObject().checkStatus(ref.tenantId());
                        }
                        Claim acquired = claim;
                        TenantUtils.execute(ref.tenantId(), () -> runOne(acquired));
                    } catch (Exception e) {
                        log.error("分类任务执行失败。job={}, tenant={}", ref.id(), ref.tenantId(), e);
                        if (claim != null) {
                            jobs.retry(claim);
                        }
                    } finally {
                        TenantContextHolder.clear();
                        UserContextHolder.clearContext();
                        inFlight.remove(ref.id());
                    }
                });
            } catch (RejectedExecutionException e) {
                inFlight.remove(ref.id());
                break;
            }
        }
    }

    void runOne(Claim claim) {
        JobRef ref = claim.job();
        try {
            TicketDO snapshot = tickets.selectById(claim.ticketId());
            if (snapshot == null || snapshot.getTenantId() == null
                || snapshot.getTenantId() != ref.tenantId()
                || snapshot.getRevision() == null || snapshot.getRevision() != claim.revision()) {
                finish(claim, null, null);
                return;
            }
            ClassifyResult result = classification.classifyWithCache(snapshot);
            finish(claim, snapshot, result);
        } catch (Exception e) {
            log.error("分类任务失败。job={}, tenant={}, attempts={}", ref.id(), ref.tenantId(),
                claim.attempts(), e);
            jobs.retry(claim);
        }
    }

    private void finish(Claim claim, TicketDO snapshot, ClassifyResult result) {
        transaction.executeWithoutResult(status -> {
            if (!jobs.lockValid(claim.job(), claim.token())) {
                return;
            }
            boolean written = snapshot != null && classification.writeBack(snapshot, result);
            jobs.finish(claim.job(), claim.token(), written);
        });
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdown();
    }
}
