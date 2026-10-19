-- Runs once, when the PostgreSQL volume is first created.
-- The "ledger" database is already created through POSTGRES_DB. Each service owns its own database.
CREATE DATABASE mockbank;
CREATE DATABASE notification;
