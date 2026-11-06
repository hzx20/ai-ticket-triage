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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;

import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * 线程池饱和时提交线程执行任务的事务边界测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyTaskCallerRunsTest {

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void callerRunsUsesNewTransactionsForDatabaseOnly() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        AtomicBoolean inNewTransaction = new AtomicBoolean();
        when(manager.getTransaction(any())).thenAnswer(invocation -> {
            assertThat(inNewTransaction.getAndSet(true)).isFalse();
            return new SimpleTransactionStatus();
        });
        doAnswer(invocation -> {
            assertThat(inNewTransaction.getAndSet(false)).isTrue();
            return null;
        }).when(manager).commit(any());
        TicketMapper mapper = mock(TicketMapper.class);
        TicketDO ticket = new TicketDO();
        ticket.setId(99L);
        ticket.setTitle("标题");
        ticket.setContent("内容");
        when(mapper.selectById(99L)).thenAnswer(invocation -> {
            assertThat(inNewTransaction.get()).isTrue();
            return ticket;
        });
        when(mapper.update(any(TicketDO.class), any())).thenAnswer(invocation -> {
            assertThat(inNewTransaction.get()).isTrue();
            return 1;
        });
        TicketClassifier classifier = mock(TicketClassifier.class);
        when(classifier.classify("标题", "内容")).thenAnswer(invocation -> {
            assertThat(inNewTransaction.get()).isFalse();
            return ClassifyResult.ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.HIGH, "结果",
                5);
        });
        ClassifyCache cache = mock(ClassifyCache.class);
        when(cache.get(any())).thenReturn(Optional.empty());
        when(cache.tryLock(any())).thenReturn(Optional.empty());
        when(cache.awaitResult(any(), eq(3), eq(50L))).thenReturn(Optional.empty());
        TicketClassifyTask task = new TicketClassifyTask(mapper, classifier, cache, manager);
        ThreadPoolTaskExecutor original = (ThreadPoolTaskExecutor) ReflectionTestUtils
            .getField(task, "executor");
        ThreadPoolTaskExecutor callerRuns = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(callerRuns).execute(any(Runnable.class));
        ReflectionTestUtils.setField(task, "executor", callerRuns);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            task.submit(99L);
        } finally {
            original.shutdown();
        }
        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor
            .forClass(TransactionDefinition.class);
        verify(manager, times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues()).allSatisfy(definition -> assertThat(definition
            .getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW));
        verify(manager, times(2)).commit(any());
    }

    @Test
    void callerRunsSuspendsCommittedConnectionForEachDatabaseOperation() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection outerConnection = mock(Connection.class);
        Connection readConnection = mock(Connection.class);
        Connection writeConnection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(outerConnection, readConnection,
            writeConnection);
        when(outerConnection.getAutoCommit()).thenReturn(true);
        when(readConnection.getAutoCommit()).thenReturn(true);
        when(writeConnection.getAutoCommit()).thenReturn(true);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        TicketMapper mapper = mock(TicketMapper.class);
        TicketDO ticket = new TicketDO();
        ticket.setId(12L);
        ticket.setTitle("标题");
        ticket.setContent("内容");
        when(mapper.selectById(12L)).thenAnswer(invocation -> {
            assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(readConnection);
            return ticket;
        });
        when(mapper.update(any(TicketDO.class), any())).thenAnswer(invocation -> {
            assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(writeConnection);
            return 1;
        });
        TicketClassifier classifier = mock(TicketClassifier.class);
        when(classifier.classify("标题", "内容")).thenAnswer(invocation -> {
            assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(outerConnection);
            return ClassifyResult.ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.HIGH, "结果",
                5);
        });
        ClassifyCache cache = mock(ClassifyCache.class);
        when(cache.get(any())).thenReturn(Optional.empty());
        when(cache.tryLock(any())).thenReturn(Optional.empty());
        when(cache.awaitResult(any(), eq(3), eq(50L))).thenReturn(Optional.empty());
        TicketClassifyTask task = new TicketClassifyTask(mapper, classifier, cache, manager);
        ThreadPoolTaskExecutor original = (ThreadPoolTaskExecutor) ReflectionTestUtils
            .getField(task, "executor");
        ThreadPoolTaskExecutor callerRuns = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(callerRuns).execute(any(Runnable.class));
        ReflectionTestUtils.setField(task, "executor", callerRuns);
        try {
            new TransactionTemplate(manager).executeWithoutResult(status -> {
                TransactionSynchronizationManager
                    .registerSynchronization(new TransactionSynchronization() {

                        @Override
                        public void afterCommit() {
                            task.submit(12L);
                        }
                    });
            });
        } finally {
            original.shutdown();
        }
        verify(outerConnection).commit();
        verify(readConnection).commit();
        verify(writeConnection).commit();
    }
}
