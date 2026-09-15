CREATE TABLE user_progress (
    user_id UUID PRIMARY KEY REFERENCES app_users(id) ON DELETE CASCADE,
    revision BIGINT NOT NULL DEFAULT 0,
    document JSONB,
    last_write_id UUID,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
