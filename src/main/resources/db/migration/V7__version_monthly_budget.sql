ALTER TABLE monthly_budget ADD COLUMN version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0);
