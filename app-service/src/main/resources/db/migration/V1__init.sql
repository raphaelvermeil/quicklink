CREATE SEQUENCE link_id_seq START WITH 14776336 INCREMENT BY 1;

   CREATE TABLE link (
     id           BIGINT PRIMARY KEY DEFAULT nextval('link_id_seq'),
     short_code   VARCHAR(16) NOT NULL UNIQUE,
     target_type  VARCHAR(8)  NOT NULL,          -- 'URL' or 'MEDIA'
     target_url   TEXT,                           -- when target_type = 'URL'
     media_asset_id BIGINT,                       -- when target_type = 'MEDIA' (Stage 4)
     created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
     expires_at   TIMESTAMPTZ
   );