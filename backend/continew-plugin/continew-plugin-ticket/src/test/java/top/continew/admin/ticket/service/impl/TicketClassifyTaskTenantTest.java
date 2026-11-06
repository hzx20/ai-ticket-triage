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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.starter.extension.tenant.context.TenantContext;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步分类任务的租户上下文隔离测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyTaskTenantTest {

    @AfterEach
    void clearContext() {
        TenantContextHolder.clear();
    }

    @Test
    void reusedWorkerSeesContextOfEachSubmission() throws Exception {
        TicketMapper mapper = mock(TicketMapper.class);
        BlockingQueue<String> observed = new LinkedBlockingQueue<>();
        when(mapper.selectById(anyLong())).thenAnswer(invocation -> {
            observed.add(String.valueOf(TenantContextHolder.getTenantId()));
            return null;
        });
        TicketClassifyTask task = new TicketClassifyTask(mapper, mock(TicketClassifier.class), mock(
            ClassifyCache.class), mock(PlatformTransactionManager.class));
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) ReflectionTestUtils
            .getField(task, "executor");
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        try {
            TenantContextHolder.setContext(context(11L));
            task.submit(1L);
            assertThat(observed.poll(5, TimeUnit.SECONDS)).isEqualTo("11");
            TenantContextHolder.setContext(context(22L));
            task.submit(2L);
            assertThat(observed.poll(5, TimeUnit.SECONDS)).isEqualTo("22");
            TenantContextHolder.clear();
            task.submit(3L);
            assertThat(observed.poll(5, TimeUnit.SECONDS)).isEqualTo("null");
        } finally {
            task.shutdown();
        }
    }

    @Test
    void callerRunsKeepsSubmittingThreadsContext() {
        TicketMapper mapper = mock(TicketMapper.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(org.mockito.ArgumentMatchers.any(Runnable.class));
        TicketClassifyTask task = new TicketClassifyTask(mapper, mock(TicketClassifier.class), mock(
            ClassifyCache.class), mock(PlatformTransactionManager.class));
        ThreadPoolTaskExecutor original = (ThreadPoolTaskExecutor) ReflectionTestUtils
            .getField(task, "executor");
        ReflectionTestUtils.setField(task, "executor", executor);
        TenantContext context = context(33L);
        TenantContextHolder.setContext(context);
        TenantContextHolder.setIgnore(true);
        try {
            task.submit(1L);
            assertThat(TenantContextHolder.getContext()).isSameAs(context);
            assertThat(TenantContextHolder.isIgnore()).isTrue();
        } finally {
            original.shutdown();
        }
    }

    private TenantContext context(Long id) {
        TenantContext context = new TenantContext();
        context.setTenantId(id);
        return context;
    }
}
