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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;

import java.util.Optional;

/**
 * 验证等待者和标记持有者的分类行为。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyTaskCacheTest {

    private static final String TITLE = "登不进去";

    private static final String CONTENT = "整个组卡在加载页";

    private static final long TICKET_ID = 100L;

    private static final String MARKER = "marker-1";

    private TicketMapper ticketMapper;

    private TicketClassifier ticketClassifier;

    private ClassifyCache classifyCache;

    private String cacheKey;

    private ClassifyResult result;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(TicketMapper.class);
        ticketClassifier = mock(TicketClassifier.class);
        classifyCache = mock(ClassifyCache.class);
        cacheKey = ClassifyCache.buildKey(1L, TITLE, CONTENT);
        TicketDO ticket = new TicketDO();
        ticket.setId(TICKET_ID);
        ticket.setTenantId(1L);
        ticket.setTitle(TITLE);
        ticket.setContent(CONTENT);
        when(ticketMapper.selectById(TICKET_ID)).thenReturn(ticket);
        result = ClassifyResult
            .ofModel(TicketCategoryEnum.ACCOUNT, TicketPriorityEnum.HIGH, "多人无法登录", 1800);
        when(ticketClassifier.classify(TITLE, CONTENT)).thenReturn(result);
    }

    @Test
    @DisplayName("等待者未等到结果时可自行分类，但不能写入或删除其他人的标记")
    void waitingCallerCannotWriteOrRelease() {
        newTask().doClassify(TICKET_ID);

        verify(ticketClassifier).classify(TITLE, CONTENT);
        verify(classifyCache, never()).putIfOwned(anyString(), anyString(),
            any());
        verify(classifyCache, never()).releaseMark(anyString(), anyString());
    }

    @Test
    @DisplayName("等待者读到结果后不再调用模型")
    void waitingCallerReusesResult() {
        when(classifyCache.awaitResult(cacheKey, 3, 50L)).thenReturn(Optional.of(result));

        newTask().doClassify(TICKET_ID);

        verify(ticketClassifier, never()).classify(anyString(), anyString());
        verify(classifyCache, never()).releaseMark(anyString(), anyString());
    }

    @Test
    @DisplayName("持有者使用自己的标记写入并释放")
    void ownerWritesAndReleases() {
        when(classifyCache.tryLock(cacheKey)).thenReturn(Optional.of(MARKER));

        newTask().doClassify(TICKET_ID);

        verify(classifyCache).putIfOwned(cacheKey, MARKER, result);
        verify(classifyCache).releaseMark(cacheKey, MARKER);
    }

    @Test
    @DisplayName("模型异常时持有者仍释放自己的标记")
    void ownerReleasesOnException() {
        when(classifyCache.tryLock(cacheKey)).thenReturn(Optional.of(MARKER));
        when(ticketClassifier.classify(TITLE, CONTENT)).thenThrow(new IllegalStateException("失败"));

        newTask().doClassify(TICKET_ID);

        verify(classifyCache).releaseMark(cacheKey, MARKER);
        verify(classifyCache, never()).putIfOwned(anyString(), anyString(),
            any());
    }

    private TicketClassifyTask newTask() {
        return new TicketClassifyTask(ticketMapper, ticketClassifier, classifyCache,
            mock(PlatformTransactionManager.class));
    }
}
