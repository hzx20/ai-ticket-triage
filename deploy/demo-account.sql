-- 只读演示账号（2026-11-03）
-- 用途：面试/分享时给对方一个只能看的账号，避免拿到超管误删数据
--
-- 两个关键点：
-- 1. data_scope = 1（全部数据权限）。如果沿用默认的 4（仅本人），
--    demo 登录后看不到 admin 创建的工单，演示就没内容了。
-- 2. 「只读」是靠菜单权限实现的：只授 6000/6001（父菜单）+ 6011 列表 + 6012 详情 + 6018 统计，
--    不给 6013 新增 / 6014 修改 / 6015 删除 / 6016 导出 / 6017 重新分类。
--    前端会按权限隐藏按钮，后端接口也会返回 403。

INSERT INTO `sys_role` (`id`, `name`, `code`, `data_scope`, `description`, `sort`, `is_system`,
                        `menu_check_strictly`, `dept_check_strictly`, `create_user`, `create_time`,
                        `deleted`, `tenant_id`)
VALUES (9001, '演示访客', 'demo_guest', 1, '只读演示账号：只能查看工单列表、详情与统计', 999, b'0',
        b'1', b'1', 1, NOW(), 0, 0);

-- 密码字段存的是 bcrypt 哈希（cost 10），明文不写在这个文件里。
-- 需要重置密码时：用 Spring Security 的 BCryptPasswordEncoder.encode(明文) 生成新哈希替换即可，
-- 注意不要用在线工具生成，没人能保证那些站点不记录。
INSERT INTO `sys_user` (`id`, `username`, `nickname`, `password`, `gender`, `status`, `is_system`,
                        `dept_id`, `create_user`, `create_time`, `deleted`, `tenant_id`)
VALUES (9002, 'demo', '演示账号', '$2b$10$vmFQic7iV.DHVzydtBpbRuWOvRXDt/wOEfBvpo/3arMJsjhzH04My',
        0, 1, b'0', 1, 1, NOW(), 0, 0);

INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`, `tenant_id`)
VALUES (9003, 9002, 9001, 0);

INSERT INTO `sys_role_menu` (`role_id`, `menu_id`, `tenant_id`)
VALUES (9001, 6000, 0),
       (9001, 6001, 0),
       (9001, 6011, 0),
       (9001, 6012, 0),
       (9001, 6018, 0);

SELECT '角色' AS 表, COUNT(*) AS 条数 FROM `sys_role` WHERE `id` = 9001
UNION ALL SELECT '用户', COUNT(*) FROM `sys_user` WHERE `id` = 9002
UNION ALL SELECT '用户角色', COUNT(*) FROM `sys_user_role` WHERE `user_id` = 9002
UNION ALL SELECT '角色菜单', COUNT(*) FROM `sys_role_menu` WHERE `role_id` = 9001;
