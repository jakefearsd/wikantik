-- V062: indexes found missing by the 2026-10-22 DB/disk profiling run.
--
--   users ( uid )                       seq-scanned on every Basic-auth request (lookup by uid)
--   drift_snapshot_counts ( sweep_id )  FK column; the composite PK leads with it but the N+1 history
--                                       reads and cascade deletes want a dedicated index
--   retrieval_runs ( mode, started_at ) "latest run per mode" (existing ix_retrieval_runs_mode is mode only)
--   kg_proposals ( created )            partial, for the judge claim:
--                                       WHERE status = 'pending' AND machine_status IS NULL ORDER BY created
--                                       (each tick seq-scanned all proposals to return 0 rows)
--
-- Plain CREATE INDEX (not CONCURRENTLY): migrate.sh applies each file as one script, and these tables are small.

CREATE INDEX IF NOT EXISTS ix_users_uid
    ON users ( uid );

CREATE INDEX IF NOT EXISTS ix_drift_snapshot_counts_sweep
    ON drift_snapshot_counts ( sweep_id );

CREATE INDEX IF NOT EXISTS ix_retrieval_runs_mode_started
    ON retrieval_runs ( mode, started_at DESC );

CREATE INDEX IF NOT EXISTS ix_kg_proposals_judge_pending
    ON kg_proposals ( created )
    WHERE status = 'pending' AND machine_status IS NULL;
