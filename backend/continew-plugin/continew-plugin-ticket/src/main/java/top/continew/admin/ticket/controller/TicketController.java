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

package top.continew.admin.ticket.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import top.continew.admin.common.base.controller.BaseController;
import top.continew.admin.ticket.model.query.TicketQuery;
import top.continew.admin.ticket.model.req.TicketReq;
import top.continew.admin.ticket.model.resp.TicketResp;
import top.continew.admin.ticket.model.resp.TicketStatisticsResp;
import top.continew.admin.ticket.service.TicketService;
import top.continew.starter.extension.crud.annotation.CrudRequestMapping;
import top.continew.starter.extension.crud.enums.Api;

/**
 * 工单管理 API
 *
 * <p>
 * {@code @CrudRequestMapping} 声明需要哪些标准接口，框架据此自动生成：
 * 分页查询（PAGE）、详情（GET）、新增（CREATE）、修改（UPDATE）、
 * 删除（DELETE）、批量删除（BATCH_DELETE）、导出（EXPORT）。
 * 这些接口的权限校验、参数校验、响应包装全部由框架统一处理，不用手写。
 * 下面两个方法是不在标准 CRUD 里的自定义接口。
 * </p>
 *
 * @author hzx20
 * @since 1.0.0
 */
@Tag(name = "工单管理 API")
@RestController
@CrudRequestMapping(value = "/ticket/manage",
    api = {Api.PAGE, Api.GET, Api.CREATE, Api.UPDATE, Api.DELETE, Api.BATCH_DELETE, Api.EXPORT})
public class TicketController
    extends BaseController<TicketService, TicketResp, TicketResp, TicketQuery, TicketReq> {

    @Operation(summary = "重新分类", description = "对指定工单重新调用模型判定分类和优先级")
    @SaCheckPermission("ticket:manage:classify")
    @PostMapping("/{id}/classify")
    public void classify(@PathVariable Long id) {
        baseService.classify(id);
    }

    @Operation(summary = "工单统计", description = "各分类、各优先级数量，以及模型兜底率与平均耗时")
    @SaCheckPermission("ticket:manage:statistics")
    @GetMapping("/statistics")
    public TicketStatisticsResp statistics() {
        return baseService.statistics();
    }
}
