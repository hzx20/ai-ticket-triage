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

package top.continew.admin.ticket.service;

import top.continew.admin.common.base.service.BaseService;
import top.continew.admin.ticket.model.entity.TicketDO;
import top.continew.admin.ticket.model.query.TicketQuery;
import top.continew.admin.ticket.model.req.TicketReq;
import top.continew.admin.ticket.model.resp.TicketResp;
import top.continew.admin.ticket.model.resp.TicketStatisticsResp;
import top.continew.starter.data.service.IService;

import java.util.List;
import java.util.Map;

/**
 * 工单业务接口
 *
 * @author hzx20
 * @since 1.0.0
 */
public interface TicketService
    extends BaseService<TicketResp, TicketResp, TicketQuery, TicketReq>, IService<TicketDO> {

    /**
     * 对指定工单重新做一次分类（同步执行，用于人工触发重新判定）
     *
     * @param id 工单 ID
     */
    void classify(Long id);

    /**
     * 获取可见工单当前版本的分类任务状态。
     *
     * @param ids 工单 ID，最多 100 个
     * @return 工单 ID 到任务状态的映射
     */
    Map<Long, String> classificationStatuses(List<Long> ids);

    /**
     * 工单统计
     *
     * @return 统计数据
     */
    TicketStatisticsResp statistics();
}
