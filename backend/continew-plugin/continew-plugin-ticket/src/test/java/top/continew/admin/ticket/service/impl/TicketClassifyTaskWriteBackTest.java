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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import top.continew.admin.ticket.ai.ClassifyCache;
import top.continew.admin.ticket.ai.ClassifyResult;
import top.continew.admin.ticket.ai.TicketClassifier;
import top.continew.admin.ticket.enums.TicketCategoryEnum;
import top.continew.admin.ticket.enums.TicketPriorityEnum;
import top.continew.admin.ticket.mapper.TicketMapper;
import top.continew.admin.ticket.model.entity.TicketDO;

import java.util.Map;
import java.util.Optional;

/**
 * 防止过期分类结果覆盖人工修改的条件更新测试。
 *
 * @author hzx20
 * @since 1.1.0
 */
class TicketClassifyTaskWriteBackTest {

    @Test
    void writeBackUsesOriginalFieldsAsAtomicConditions() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
            TicketDO.class);
        TicketMapper mapper = mock(TicketMapper.class);
        TicketClassifier classifier = mock(TicketClassifier.class);
        ClassifyCache cache = mock(ClassifyCache.class);
        TicketDO snapshot = new TicketDO();
        snapshot.setId(21L);
        snapshot.setTenantId(3L);
        snapshot.setTitle("原始标题");
        snapshot.setContent("原始内容");
        snapshot.setCategory("data");
        snapshot.setPriority(2);
        when(mapper.selectById(21L)).thenReturn(snapshot);
        when(cache.get(any())).thenReturn(Optional.empty());
        when(cache.tryLock(any())).thenReturn(Optional.empty());
        when(cache.awaitResult(any(), eq(3), eq(50L))).thenReturn(Optional.empty());
        when(classifier.classify("原始标题", "原始内容"))
            .thenReturn(ClassifyResult.ofModel(TicketCategoryEnum.FAULT, TicketPriorityEnum.HIGH,
                "模型结果", 5));

        // 模拟查询之后用户已经修改，数据库的条件 UPDATE 返回 0。
        when(mapper.update(any(TicketDO.class), any())).thenReturn(0);
        TicketClassifyTask task = new TicketClassifyTask(mapper, classifier, cache,
            mock(PlatformTransactionManager.class));
        try {
            task.doClassify(21L);
        } finally {
            task.shutdown();
        }

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<TicketDO>> condition = ArgumentCaptor
            .forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(any(TicketDO.class), condition.capture());
        String sql = condition.getValue().getSqlSegment();
        Map<String, Object> values = condition.getValue().getParamNameValuePairs();
        assertThat(sql).contains("id =", "title =", "content =", "category =", "priority =",
            "update_time IS NULL");
        assertThat(values.values()).contains(21L, "原始标题", "原始内容", "data", 2);
    }
}
