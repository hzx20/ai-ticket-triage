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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import top.continew.admin.common.api.tenant.TenantApi;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRepository.Claim;
import top.continew.admin.ticket.service.impl.TicketClassifyJobRepository.JobRef;

/**
 * 持久任务写回时的租约、租户和版本保护测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyJobRunnerTest {

    private final JobRef ref = new JobRef("job", 1L);

    private final Claim claim = new Claim(ref, "token", 9L, 3L, 1);

    private TicketClassifyJobRepository jobs;

    private TicketMapper mapper;

    private TicketClassifyTask classifier;

    private TicketClassifyJobRunner runner;

    private ClassifyResult result;

    @BeforeEach
    void setUp() {
        jobs = mock(TicketClassifyJobRepository.class);
        mapper = mock(TicketMapper.class);
        classifier = mock(TicketClassifyTask.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        runner =
            new TicketClassifyJobRunner(jobs, mapper, classifier, new StaticListableBeanFactory()
                .getBeanProvider(TenantApi.class), transactionManager);
        result = ClassifyResult.ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.HIGH, "结果", 5);
    }

    @AfterEach
    void stop() {
        runner.shutdown();
    }

    @Test
    void expiredOwnerCannotWriteOrFinish() {
        TicketDO ticket = ticket(1L, 3L);
        when(mapper.selectById(9L)).thenReturn(ticket);
        when(classifier.classifyWithCache(ticket)).thenReturn(result);
        when(jobs.lockValid(ref, "token")).thenReturn(false);
        runner.runOne(claim);
        verify(classifier, never()).writeBack(any(), any());
        verify(jobs, never()).finish(any(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void changedRevisionIsSupersededWithoutCallingModel() {
        when(mapper.selectById(9L)).thenReturn(ticket(1L, 4L));
        when(jobs.lockValid(ref, "token")).thenReturn(true);
        runner.runOne(claim);
        verify(classifier, never()).classifyWithCache(any());
        verify(jobs).finish(ref, "token", false);
    }

    @Test
    void mismatchedTenantIsNeverClassified() {
        when(mapper.selectById(9L)).thenReturn(ticket(2L, 3L));
        when(jobs.lockValid(ref, "token")).thenReturn(true);
        runner.runOne(claim);
        verify(classifier, never()).classifyWithCache(any());
        verify(jobs).finish(ref, "token", false);
    }

    @Test
    void validOwnerWritesAndFinishes() {
        TicketDO ticket = ticket(1L, 3L);
        when(mapper.selectById(9L)).thenReturn(ticket);
        when(classifier.classifyWithCache(ticket)).thenReturn(result);
        when(classifier.writeBack(ticket, result)).thenReturn(true);
        when(jobs.lockValid(ref, "token")).thenReturn(true);
        runner.runOne(claim);
        verify(classifier).writeBack(ticket, result);
        verify(jobs).finish(ref, "token", true);
    }

    @Test
    void failedClassificationIsRetried() {
        TicketDO ticket = ticket(1L, 3L);
        when(mapper.selectById(9L)).thenReturn(ticket);
        when(classifier.classifyWithCache(ticket)).thenThrow(new IllegalStateException("failed"));
        runner.runOne(claim);
        verify(jobs).retry(claim);
    }

    private TicketDO ticket(long tenantId, long revision) {
        TicketDO ticket = new TicketDO();
        ticket.setId(9L);
        ticket.setTenantId(tenantId);
        ticket.setRevision(revision);
        return ticket;
    }
}
