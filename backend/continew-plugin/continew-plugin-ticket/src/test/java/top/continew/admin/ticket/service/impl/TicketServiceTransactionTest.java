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
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttribute;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.model.req.TicketReq;

/**
 * 创建工单事务与手工分类规则测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketServiceTransactionTest {

    private final TicketServiceImpl service = new TicketServiceImpl(mock(TicketClassifyTask.class),
        mock(ClassifyCache.class), mock(TicketClassifyJobRepository.class));

    @Test
    void createHasTransactionAttributeWithCheckedExceptionRollback() throws Exception {
        TransactionAttribute attribute = new AnnotationTransactionAttributeSource()
            .getTransactionAttribute(TicketServiceImpl.class.getMethod("create", TicketReq.class),
                TicketServiceImpl.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.rollbackOn(new Exception())).isTrue();
    }

    @Test
    void manualClassificationRequiresBothValidValues() {
        TicketReq req = new TicketReq();
        assertThat(service.requiresClassification(req)).isTrue();
        req.setCategory("data");
        assertThatThrownBy(() -> service.requiresClassification(req))
            .isInstanceOf(RuntimeException.class);
        req.setPriority(2);
        assertThat(service.requiresClassification(req)).isFalse();
        req.setCategory("invalid");
        assertThatThrownBy(() -> service.requiresClassification(req))
            .isInstanceOf(RuntimeException.class);
    }
}
