-- Encrypted connector credentials (CredentialEncryption P2.2, 2026-07-11).
-- ciphertext is a base64 AES-256-GCM token (iv‖ct‖tag); the master key lives in config, never here.
CREATE TABLE IF NOT EXISTS connector_credentials (
    connector_id    TEXT NOT NULL,
    credential_name TEXT NOT NULL,
    ciphertext      TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (connector_id, credential_name)
);
GRANT SELECT, INSERT, UPDATE, DELETE ON connector_credentials TO :app_user;
