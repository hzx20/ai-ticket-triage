-- liquibase formatted sql

-- changeset workbuddy:1
-- comment 初始化 AI 工单插件数据表
CREATE TABLE IF NOT EXISTS `ticket` (
    `id`            bigint        AUTO_INCREMENT        COMMENT 'ID',
    `title`         varchar(100)  NOT NULL              COMMENT '标题',
    `content`       text          NOT NULL              COMMENT '问题描述',
    `category`      varchar(30)   DEFAULT NULL          COMMENT '分类编码（account/fault/feature/data/consult/other）',
    `priority`      tinyint       DEFAULT NULL          COMMENT '优先级（1：低；2：中；3：高）',
    `status`        tinyint       NOT NULL DEFAULT 1    COMMENT '状态（1：待处理；2：处理中；3：已解决；4：已关闭）',
    `assignee_id`   bigint        DEFAULT NULL          COMMENT '处理人ID',
    `handle_remark` varchar(500)  DEFAULT NULL          COMMENT '处理备注',
    `ai_reason`     varchar(500)  DEFAULT NULL          COMMENT 'AI 判定理由',
    `ai_fallback`   bit(1)        NOT NULL DEFAULT b'0' COMMENT '是否走了规则兜底',
    `ai_cost_ms`    int           DEFAULT NULL          COMMENT 'AI 调用耗时（毫秒）',
    `create_user`   bigint        NOT NULL              COMMENT '创建人',
    `create_time`   datetime      NOT NULL              COMMENT '创建时间',
    `update_user`   bigint        DEFAULT NULL          COMMENT '修改人',
    `update_time`   datetime      DEFAULT NULL          COMMENT '修改时间',
    `deleted`       bigint        NOT NULL DEFAULT 0    COMMENT '是否已删除（0：否；id：是）',
    PRIMARY KEY (`id`),
    INDEX `idx_category` (`category`),
    INDEX `idx_status` (`status`),
    INDEX `idx_priority` (`priority`),
    INDEX `idx_create_user` (`create_user`),
    INDEX `idx_deleted` (`deleted`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='工单表';

-- changeset workbuddy:2
-- comment 初始化 AI 工单菜单
INSERT INTO `sys_menu`
(`id`, `title`, `parent_id`, `type`, `path`, `name`, `component`, `redirect`, `icon`, `is_external`, `is_cache`,
 `is_hidden`, `permission`, `sort`, `status`, `create_user`, `create_time`)
VALUES
(6000, 'AI 工单', 0, 1, '/ticket', 'Ticket', 'Layout', '/ticket/list', 'robot', b'0', b'0', b'0', NULL, 6, 1, 1, NOW()),
(6001, '工单管理', 6000, 2, '/ticket/list', 'TicketList', 'ticket/index', NULL, 'file-text', b'0', b'0', b'0', NULL, 1, 1, 1, NOW()),
(6011, '列表', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:list', 1, 1, 1, NOW()),
(6012, '详情', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:get', 2, 1, 1, NOW()),
(6013, '新增', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:create', 3, 1, 1, NOW()),
(6014, '修改', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:update', 4, 1, 1, NOW()),
(6015, '删除', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:delete', 5, 1, 1, NOW()),
(6016, '导出', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:export', 6, 1, 1, NOW()),
(6017, '重新分类', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:classify', 7, 1, 1, NOW()),
(6018, '统计', 6001, 3, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'ticket:statistics', 8, 1, 1, NOW());

-- changeset workbuddy:3
-- comment 将 AI 工单菜单授权给超级管理员角色
INSERT INTO `sys_role_menu` (`role_id`, `menu_id`)
VALUES (1, 6000), (1, 6001), (1, 6011), (1, 6012), (1, 6013),
       (1, 6014), (1, 6015), (1, 6016), (1, 6017), (1, 6018);

-- changeset workbuddy:4
-- comment 修正工单接口权限标识：框架要求 @CrudRequestMapping 路径至少两段（/模块/资源），
-- comment 权限前缀由路径推导，因此控制器路径改为 /ticket/manage，对应前缀 ticket:manage
UPDATE `sys_menu` SET `permission` = 'ticket:manage:list' WHERE `id` = 6011;
UPDATE `sys_menu` SET `permission` = 'ticket:manage:get' WHERE `id` = 6012;
UPDATE `sys_menu` SET `permission` = 'ticket:manage:create' WHERE `id` = 6013;
UPDATE `sys_menu` SET `permission` = 'ticket:manage:update' WHERE `id` = 6014;
UPDATE `sys_menu` SET `permission` = 'ticket:manage:delete' WHERE `id` = 6015;
UPDATE `sys_menu` SET `permission` = 'ticket:manage:export' WHERE `id` = 6016;

-- changeset workbuddy:5
-- comment 补充租户字段：项目开启了行级多租户隔离，业务表没有 tenant_id 会导致查询报
-- comment "Unknown column 'tenant_id' in 'where clause'"
ALTER TABLE `ticket`
    ADD COLUMN `tenant_id` bigint NOT NULL DEFAULT 0 COMMENT '租户ID' AFTER `deleted`,
    ADD INDEX `idx_tenant_id` (`tenant_id`);

-- changeset workbuddy:6
-- comment 自定义接口的权限标识改成三段式。原因：超级管理员的权限是通配符 *:*:*，
-- comment 而 Sa-Token 的通配匹配按段数进行，两段的 ticket:statistics 匹配不上 *:*:*，会返回 403
UPDATE `sys_menu` SET `permission` = 'ticket:manage:classify' WHERE `id` = 6017;
UPDATE `sys_menu` SET `permission` = 'ticket:manage:statistics' WHERE `id` = 6018;

-- changeset workbuddy:7
-- comment 新增 AI 结果来源字段，区分「模型判定 / 规则兜底 / 缓存命中」
-- comment 动机：加了缓存之后，ai_cost_ms 在缓存命中时写的是当初调模型的耗时，
-- comment 导致「AI 平均耗时」这个指标看不出缓存有任何收益。加来源字段后可以按来源分组统计，
-- comment 同时缓存命中改为记录真实读取耗时（几毫秒），两个维度才都能被证明
ALTER TABLE `ticket`
    ADD COLUMN `ai_source` varchar(16) DEFAULT NULL COMMENT 'AI 结果来源（MODEL：模型判定；RULE：规则兜底；CACHE：缓存命中）' AFTER `ai_cost_ms`,
    ADD INDEX `idx_ai_source` (`ai_source`);

