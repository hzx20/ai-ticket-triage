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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.model.req.TicketReq;

/**
 * 工单人工编辑与重分类的版本和来源测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketManualEditTest {

    private TicketMapper mapper;

    private TicketClassifyJobRepository jobs;

    private TicketServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            TicketDO.class);
        mapper = mock(TicketMapper.class);
        jobs = mock(TicketClassifyJobRepository.class);
        service =
            new TicketServiceImpl(mock(TicketClassifyTask.class), mock(ClassifyCache.class), jobs);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
    }

    @Test
    void invalidStatusIsRejectedBeforeCreateOrUpdate() {
        TicketReq req = request(1L);
        req.setStatus(5);
        assertThatThrownBy(() -> service.update(req, 9L)).hasMessageContaining("工单状态无效");
        req.setRevision(null);
        req.setStatus(0);
        assertThatThrownBy(() -> service.create(req)).hasMessageContaining("工单状态无效");
        verify(mapper, never()).selectById(anyLong());
    }

    @Test
    void staleRevisionFailsWithoutEnqueueing() {
        TicketDO snapshot = ticket(2L);
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(0);
        TicketReq req = request(1L);
        assertThatThrownBy(() -> service.update(req, 9L)).hasMessageContaining("刷新后重试");
        verify(jobs, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void unclassifiedEditEnqueuesNewRevision() {
        when(mapper.selectById(9L)).thenReturn(ticket(2L));
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        service.update(request(2L), 9L);
        verify(jobs).enqueue(3L, 9L, 3L);
    }

    @Test
    void manualEditClearsOldAiAttributionAndDoesNotEnqueue() {
        TicketDO snapshot = ticket(4L);
        snapshot.setCategory("data");
        snapshot.setPriority(2);
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        TicketReq req = request(4L);
        req.setCategory("fault");
        req.setPriority(3);
        service.update(req, 9L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TicketDO>> condition = ArgumentCaptor
            .forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(any(TicketDO.class), condition.capture());
        String setSql = condition.getValue().getSqlSet();
        assertThat(setSql).contains("revision = revision + 1", "ai_source=", "ai_reason=",
            "ai_cost_ms=", "ai_fallback=");
        assertThat(condition.getValue().getParamNameValuePairs().values()).contains("MANUAL",
            false);
        verify(jobs, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void manualReclassificationDoesNotUseResultCache() {
        TicketDO snapshot = ticket(1L);
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        TicketClassifier classifier = mock(TicketClassifier.class);
        when(classifier.classify("标题", "正文")).thenReturn(ClassifyResult
            .ofRule(TicketCategoryEnum.OTHER, TicketPriorityEnum.MEDIUM, "规则兜底", 2));
        ClassifyCache cache = mock(ClassifyCache.class);
        TicketClassifyTask task = new TicketClassifyTask(mapper, classifier, cache);
        task.classifyManually(9L);
        verify(classifier).classify("标题", "正文");
        verify(cache, never()).get(anyString());
    }

    @Test
    void changingTextClearsAiClassificationAndEnqueuesNewRevision() {
        TicketDO snapshot = ticket(4L);
        snapshot.setCategory("data");
        snapshot.setPriority(2);
        snapshot.setAiSource("MODEL");
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        service.update(request(4L), 9L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TicketDO>> condition = ArgumentCaptor
            .forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(any(TicketDO.class), condition.capture());
        assertThat(condition.getValue().getSqlSet()).contains("category=", "priority=",
            "ai_source=", "ai_reason=", "ai_cost_ms=");
        verify(jobs).enqueue(3L, 9L, 5L);
    }

    @Test
    void changingTextPreservesExistingManualClassification() {
        TicketDO snapshot = ticket(4L);
        snapshot.setCategory("data");
        snapshot.setPriority(2);
        snapshot.setAiSource("MANUAL");
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        service.update(request(4L), 9L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TicketDO>> condition = ArgumentCaptor
            .forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(any(TicketDO.class), condition.capture());
        assertThat(condition.getValue().getSqlSet()).doesNotContain("category=", "priority=",
            "ai_source=");
        verify(jobs, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void fullFormWithoutClassificationChangesPreservesAiSource() {
        TicketDO snapshot = ticket(4L);
        snapshot.setCategory("data");
        snapshot.setPriority(2);
        snapshot.setAiSource("MODEL");
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        TicketReq req = request(4L);
        req.setTitle("标题");
        req.setContent("正文");
        req.setCategory("data");
        req.setPriority(2);
        service.update(req, 9L);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TicketDO>> condition = ArgumentCaptor
            .forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(any(TicketDO.class), condition.capture());
        assertThat(condition.getValue().getSqlSet()).doesNotContain("ai_source=", "ai_reason=",
            "ai_cost_ms=");
        verify(jobs, never()).enqueue(anyLong(), anyLong(), anyLong());
    }

    @Test
    void fullFormWithChangedTextInvalidatesUnchangedAiClassification() {
        TicketDO snapshot = ticket(4L);
        snapshot.setCategory("data");
        snapshot.setPriority(2);
        snapshot.setAiSource("MODEL");
        when(mapper.selectById(9L)).thenReturn(snapshot);
        when(mapper.update(any(TicketDO.class), any())).thenReturn(1);
        TicketReq req = request(4L);
        req.setCategory("data");
        req.setPriority(2);
        service.update(req, 9L);
        ArgumentCaptor<TicketDO> update = ArgumentCaptor.forClass(TicketDO.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TicketDO>> condition = ArgumentCaptor
            .forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(update.capture(), condition.capture());
        assertThat(update.getValue().getCategory()).isNull();
        assertThat(update.getValue().getPriority()).isNull();
        assertThat(condition.getValue().getSqlSet()).contains("category=", "priority=",
            "ai_source=", "ai_reason=", "ai_cost_ms=");
        verify(jobs).enqueue(3L, 9L, 5L);
    }

    private TicketDO ticket(long revision) {
        TicketDO ticket = new TicketDO();
        ticket.setId(9L);
        ticket.setTenantId(3L);
        ticket.setRevision(revision);
        ticket.setTitle("标题");
        ticket.setContent("正文");
        return ticket;
    }

    private TicketReq request(long revision) {
        TicketReq req = new TicketReq();
        req.setTitle("新标题");
        req.setContent("新正文");
        req.setRevision(revision);
        return req;
    }
}
