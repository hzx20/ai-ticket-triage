-- liquibase formatted sql

-- changeset codex:ticket-revision-1
-- comment 防止工单修改与异步分类并发写入时覆盖较新的结果
ALTER TABLE `ticket` ADD COLUMN `revision` bigint NOT NULL DEFAULT 0;
-- rollback ALTER TABLE `ticket` DROP COLUMN `revision`;
