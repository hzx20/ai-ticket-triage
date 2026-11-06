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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.model.req.TicketReq;

/**
 * 创建工单事务和提交分类任务的测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketServiceTransactionTest {

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createHasTransactionAttributeWithCheckedExceptionRollback() throws Exception {
        TransactionAttribute attribute = new AnnotationTransactionAttributeSource()
            .getTransactionAttribute(TicketServiceImpl.class.getMethod("create", TicketReq.class),
                TicketServiceImpl.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.rollbackOn(new Exception())).isTrue();
    }

    @Test
    void classificationIsSubmittedOnlyAfterCommit() {
        TicketClassifyTask task = mock(TicketClassifyTask.class);
        TicketServiceImpl service = new TicketServiceImpl(task, mock(ClassifyCache.class));
        TransactionSynchronizationManager.initSynchronization();
        service.submitAfterCommit(7L);
        verify(task, never()).submit(7L);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager
            .getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(task).submit(7L);
    }

    @Test
    void rollbackDoesNotSubmitClassification() {
        TicketClassifyTask task = mock(TicketClassifyTask.class);
        TicketServiceImpl service = new TicketServiceImpl(task, mock(ClassifyCache.class));
        TransactionSynchronizationManager.initSynchronization();
        service.submitAfterCommit(8L);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager
            .getSynchronizations()) {
            synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
        }
        verify(task, never()).submit(8L);
    }
}
