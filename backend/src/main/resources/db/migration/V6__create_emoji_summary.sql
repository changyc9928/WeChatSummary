-- Create emoji_summaries table for animated stickers (localType 47)
CREATE TABLE IF NOT EXISTS emoji_summary
(
    id VARCHAR(255) PRIMARY KEY,
    emoji_hash VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Add unique constraint on emoji_hash to prevent duplicate processing
ALTER TABLE emoji_summary
    ADD CONSTRAINT uq_emoji_summary_emoji_hash UNIQUE (emoji_hash);

-- Add an index on emoji_hash for faster cache-miss lookups
CREATE INDEX IF NOT EXISTS idx_emoji_summary_emoji_hash ON emoji_summary (emoji_hash);

-- Add a comment for documentation
COMMENT ON TABLE emoji_summary IS 'Stores LLM vision summary of processed animated sticker (emoji) files';
