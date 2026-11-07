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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.controller.TicketController;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.starter.extension.tenant.context.TenantContext;
import top.continew.starter.extension.tenant.context.TenantContextHolder;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 状态查询接口权限与租户过滤测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketStatusVisibilityTest {

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
        TenantContext tenant = new TenantContext();
        tenant.setTenantId(11L);
        TenantContextHolder.setContext(tenant);
    }

    @AfterEach
    void cleanUp() {
        TenantContextHolder.clear();
    }

    @Test
    void statusEndpointRequiresListPermission() throws Exception {
        SaCheckPermission permission = TicketController.class
            .getMethod("classificationStatuses", List.class).getAnnotation(SaCheckPermission.class);
        assertThat(permission.value()).containsExactly("ticket:manage:list");
    }

    @Test
    void onlyVisibleCurrentTenantTicketsArePassedToJobQuery() {
        TicketDO visible = new TicketDO();
        visible.setId(8L);
        visible.setTenantId(11L);
        visible.setRevision(4L);
        when(mapper.selectList(any())).thenReturn(List.of(visible));
        when(jobs.statuses(11L, Map.of(8L, 4L))).thenReturn(Map.of(8L, "FAILED"));
        assertThat(service.classificationStatuses(List.of(8L, 9L)))
            .containsExactlyEntriesOf(Map.of(8L, "FAILED"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<TicketDO>> query = ArgumentCaptor
            .forClass(LambdaQueryWrapper.class);
        verify(mapper).selectList(query.capture());
        assertThat(query.getValue().getSqlSegment()).contains("tenant_id =", "id IN");
        assertThat(query.getValue().getParamNameValuePairs().values()).contains(11L, 8L, 9L);
        verify(jobs).statuses(11L, Map.of(8L, 4L));
    }

    @Test
    void requestIsLimitedToOneHundredIds() {
        assertThatThrownBy(() -> service.classificationStatuses(Collections.nCopies(101, 1L)))
            .hasMessageContaining("100");
    }
}
