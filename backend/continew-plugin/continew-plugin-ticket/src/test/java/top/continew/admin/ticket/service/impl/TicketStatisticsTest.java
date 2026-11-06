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
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.model.resp.TicketStatisticsResp;

import java.util.List;

/**
 * 兜底率统计口径测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketStatisticsTest {

    @Test
    void fallbackRateExcludesTicketsStillAwaitingClassification() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            TicketDO.class);
        TicketMapper mapper = mock(TicketMapper.class);
        // 10 张工单中仅 4 张完成分类，其中 1 张走规则；待分类工单不能稀释比例。
        when(mapper.selectCount(nullable(Wrapper.class))).thenReturn(10L, 6L, 4L, 1L);
        when(mapper.selectMaps(any())).thenReturn(List.of());
        ClassifyCache cache = mock(ClassifyCache.class);
        when(cache.stats()).thenReturn(new ClassifyCache.Stats(0, 0, 0, 0, 0));
        TicketServiceImpl service = new TicketServiceImpl(mock(TicketClassifyTask.class), cache);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);

        TicketStatisticsResp result = service.statistics();

        assertThat(result.getTotal()).isEqualTo(10L);
        assertThat(result.getFallbackCount()).isEqualTo(1L);
        assertThat(result.getFallbackRate()).isEqualTo(0.25D);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<TicketDO>> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, times(4)).selectCount(queries.capture());
        assertThat(queries.getAllValues().get(2).getSqlSegment())
            .contains("ai_cost_ms IS NOT NULL");
        assertThat(queries.getAllValues().get(3).getSqlSegment())
            .contains("ai_cost_ms IS NOT NULL", "ai_fallback =");
    }
}
