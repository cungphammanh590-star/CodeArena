-- Optional extra init scripts for CodeArena Postgres.
-- Primary DB is created via POSTGRES_DB=codearena.
-- Add schemas / extensions here as services land.

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
