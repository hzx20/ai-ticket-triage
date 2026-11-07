-- liquibase formatted sql

-- changeset codex:ticket-classify-job-1
-- comment Persist classification work in the same transaction as ticket creation
CREATE TABLE IF NOT EXISTS ticket_classify_job (
    id                varchar(36) PRIMARY KEY,
    tenant_id         bigint NOT NULL,
    ticket_id         bigint NOT NULL,
    expected_revision bigint NOT NULL,
    status            varchar(16) NOT NULL,
    attempts          integer NOT NULL DEFAULT 0,
    next_run_at       timestamp(3) NOT NULL,
    lease_until       timestamp(3),
    lease_token       varchar(36),
    created_at        timestamp(3) NOT NULL,
    updated_at        timestamp(3) NOT NULL
);
CREATE INDEX idx_ticket_job_pending ON ticket_classify_job (status, next_run_at);
CREATE INDEX idx_ticket_job_lease ON ticket_classify_job (status, lease_until);
CREATE INDEX idx_ticket_job_tenant_ticket ON ticket_classify_job (tenant_id, ticket_id);

-- changeset codex:ticket-classify-job-backfill-1
-- comment Recover only open historical tickets with no classification or prior AI result
INSERT INTO ticket_classify_job
    (id, tenant_id, ticket_id, expected_revision, status, attempts, next_run_at, created_at, updated_at)
SELECT CONCAT('legacy-', t.id), t.tenant_id, t.id, t.revision, 'PENDING', 0,
       CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
FROM ticket t
WHERE t.deleted = 0 AND t.status IN (1, 2) AND t.category IS NULL AND t.priority IS NULL
  AND t.ai_source IS NULL AND t.ai_cost_ms IS NULL
  AND NOT EXISTS (SELECT 1 FROM ticket_classify_job j WHERE j.tenant_id = t.tenant_id
                  AND j.ticket_id = t.id AND j.expected_revision = t.revision);
