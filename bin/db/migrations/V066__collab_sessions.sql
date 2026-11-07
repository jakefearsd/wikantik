-- V066: collaborative-editing drafts (docs/MultiEdit.MD section 10, as amended in the Phase 1 plan).
-- One draft per page (collab_session), its ordered update log, last-writer-wins meta ops and periodic
-- snapshots. collab_update.(session_id, version) and collab_meta_op.(session_id, seq) primary keys are the
-- ordering arbiter; the store also bumps head_version / meta_seq in the same transaction.
-- page_version_contributor is for Phase 3 (per-version contributors, rendered through PublicIdentity).
--
-- Idempotent: CREATE TABLE IF NOT EXISTS, GRANT is repeatable. DDL-only (no backfill).

CREATE TABLE IF NOT EXISTS collab_session (
    id                           UUID PRIMARY KEY,
    page_name                    TEXT        NOT NULL UNIQUE,
    status                       TEXT        NOT NULL DEFAULT 'active' CHECK ( status IN ( 'active', 'frozen' ) ),
    base_page_version            INTEGER     NOT NULL,
    base_body                    TEXT        NOT NULL,
    base_meta                    JSONB       NOT NULL,
    base_content_hash            TEXT        NOT NULL,
    head_version                 INTEGER     NOT NULL DEFAULT 0,
    meta_seq                     BIGINT      NOT NULL DEFAULT 0,
    has_unpublished              BOOLEAN     NOT NULL DEFAULT FALSE,
    pending_publish_version      INTEGER,
    pending_publish_base_version INTEGER,
    pending_publish_author       TEXT,
    created_by                   TEXT        NOT NULL,
    created_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_edit_at                 TIMESTAMPTZ,
    CONSTRAINT collab_session_pending_all_or_none CHECK (
        ( pending_publish_version IS NULL AND pending_publish_base_version IS NULL AND pending_publish_author IS NULL )
        OR ( pending_publish_version IS NOT NULL AND pending_publish_base_version IS NOT NULL
             AND pending_publish_author IS NOT NULL ) )
);

CREATE TABLE IF NOT EXISTS collab_update (
    session_id UUID        NOT NULL REFERENCES collab_session ( id ) ON DELETE CASCADE,
    version    INTEGER     NOT NULL,
    kind       TEXT        NOT NULL CHECK ( kind IN ( 'text', 'server' ) ),
    client_id  TEXT        NOT NULL,
    author_key TEXT,
    changes    JSONB       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY ( session_id, version )
);

CREATE TABLE IF NOT EXISTS collab_meta_op (
    session_id UUID        NOT NULL REFERENCES collab_session ( id ) ON DELETE CASCADE,
    seq        BIGINT      NOT NULL,
    author_key TEXT,
    field      TEXT        NOT NULL,
    value      JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY ( session_id, seq )
);

CREATE TABLE IF NOT EXISTS collab_snapshot (
    session_id        UUID    NOT NULL REFERENCES collab_session ( id ) ON DELETE CASCADE,
    version           INTEGER NOT NULL,
    meta_seq          BIGINT  NOT NULL,
    base_page_version INTEGER NOT NULL,
    body              TEXT    NOT NULL,
    meta              JSONB   NOT NULL,
    local_changes     JSONB   NOT NULL,
    PRIMARY KEY ( session_id, version )
);

CREATE TABLE IF NOT EXISTS page_version_contributor (
    canonical_id TEXT    NOT NULL,
    page_version INTEGER NOT NULL,
    author_key   TEXT    NOT NULL,
    PRIMARY KEY ( canonical_id, page_version, author_key )
);

GRANT SELECT, INSERT, UPDATE, DELETE ON collab_session TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON collab_update TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON collab_meta_op TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON collab_snapshot TO :app_user;
GRANT SELECT, INSERT, UPDATE, DELETE ON page_version_contributor TO :app_user;
