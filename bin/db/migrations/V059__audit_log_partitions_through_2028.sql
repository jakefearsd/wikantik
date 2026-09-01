-- V059: extend audit_log's monthly partitions.
--
-- V036 created "current + next two months" (2026-06 .. 2026-08) when it was written in
-- June 2026. Those ran out on 2026-09-01. The writer's ensurePartition() is supposed to
-- cover the gap, but it issues CREATE TABLE ... PARTITION OF, and the documented
-- deployment posture gives the application role no CREATE on schema public — see
-- JdbcAuditRepositoryTest#append_succeeds_for_least_privilege_role_when_partition_exists.
-- On such a deployment the first audit write after the last partition's end date fails
-- with "permission denied for schema public" and the tamper-evident trail silently stops
-- accepting entries.
--
-- Pre-creating the range through 2028-12 is the fix that matches the existing design:
-- migrations run as a privileged role, so partitions exist before the app ever needs
-- them, and ensurePartition() stays a no-op fast path rather than load-bearing DDL.
-- A DEFAULT partition would also prevent the failure but would then block attaching any
-- future monthly partition whose rows had already landed in it, so it is deliberately
-- not used here.
--
-- Idempotent: CREATE TABLE IF NOT EXISTS, and the loop re-derives the same names and
-- bounds on every run. Re-applying is a no-op.
DO $$
DECLARE
    start_month date := date '2026-09-01';
    end_month   date := date '2029-01-01';
    m           date;
BEGIN
    m := start_month;
    WHILE m < end_month LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF audit_log FOR VALUES FROM (%L) TO (%L)',
            'audit_log_' || to_char( m, 'YYYY_MM' ),
            m,
            m + interval '1 month'
        );
        m := m + interval '1 month';
    END LOOP;
END $$;
