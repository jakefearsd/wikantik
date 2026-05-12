-- V029__kg_edge_audit_confirm_action.sql
-- Extends the kg_edge_audit.action CHECK constraint to accept 'CONFIRM',
-- recorded when an admin elevates an edge in place to human-curated status
-- via the Edge Explorer's Confirm action (no other edit).
--
-- Idempotent: drops the prior constraint if present, then re-adds the new
-- allowed-values list. Safe to re-apply.

ALTER TABLE kg_edge_audit
    DROP CONSTRAINT IF EXISTS kg_edge_audit_action_check;

ALTER TABLE kg_edge_audit
    ADD CONSTRAINT kg_edge_audit_action_check
    CHECK (action IN ('CREATE', 'UPDATE', 'DELETE', 'CONFIRM'));
