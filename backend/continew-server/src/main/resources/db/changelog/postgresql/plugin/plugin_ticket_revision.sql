-- liquibase formatted sql

-- changeset codex:ticket-revision-1
-- comment 在已有 ticket 表上增加工单并发控制版本
ALTER TABLE ticket ADD COLUMN revision bigint NOT NULL DEFAULT 0;
-- rollback ALTER TABLE ticket DROP COLUMN revision;
