-- Minimal H2 (MySQL mode) schema for the wallet ledger tests.
-- Mirrors the user_wallets columns from src/main/resources/sql/schema.sql.
CREATE TABLE user_wallets (
  user_id BIGINT NOT NULL PRIMARY KEY,
  total_credits INT DEFAULT 0,
  frozen_credits INT DEFAULT 0,
  total_recharged INT DEFAULT 0,
  total_recharged_amount DECIMAL(12, 2) DEFAULT 0.00,
  version INT DEFAULT 0,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
