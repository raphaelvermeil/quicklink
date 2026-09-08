CREATE TABLE link_stats (
  short_code VARCHAR(16) PRIMARY KEY REFERENCES link (short_code),
  clicks     BIGINT      NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
