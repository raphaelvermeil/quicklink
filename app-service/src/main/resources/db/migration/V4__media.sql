-- V4, not V2 as the plan sketches: V2 and V3 are already applied, and Flyway
-- refuses to start on a duplicate version.

-- BIGSERIAL (identity) is correct here, unlike link.short_code in V1. There the value
-- had to exist BEFORE the insert because the short code was derived from it. Here the
-- id is only needed AFTER the insert — to point link.media_asset_id at it and to put
-- it in the queue message — which is precisely what identity generation provides.
CREATE TABLE media_asset (
  id              BIGSERIAL PRIMARY KEY,
  status          VARCHAR(12) NOT NULL,   -- PENDING | PROCESSING | READY | FAILED
  content_type    VARCHAR(64) NOT NULL,
  original_key    TEXT        NOT NULL,   -- object key in the 'originals' bucket
  processed_key   TEXT,                   -- object key in 'processed', once READY
  original_bytes  BIGINT,
  processed_bytes BIGINT,
  error_message   TEXT,                   -- populated when status = FAILED
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- link.media_asset_id has existed since V1 but could not be constrained then,
-- because this table did not exist. The natural insert order (asset, then link)
-- already satisfies it.
ALTER TABLE link
  ADD CONSTRAINT link_media_asset_fk
  FOREIGN KEY (media_asset_id) REFERENCES media_asset (id);
