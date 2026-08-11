-- Create video_summaries table
CREATE TABLE IF NOT EXISTS video_summaries
(
    id VARCHAR(255) PRIMARY KEY,
    file_hash VARCHAR(255) NOT NULL,
    file_path TEXT NOT NULL,
    transcript TEXT,
    summary TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
);

-- Add unique constraint on file_hash to prevent duplicate processing
ALTER TABLE video_summaries
    ADD CONSTRAINT uq_video_summaries_file_hash UNIQUE (file_hash);

-- Add an index on file_hash for faster cache-miss lookups
CREATE INDEX IF NOT EXISTS idx_video_summaries_file_hash ON video_summaries (file_hash);

-- Add a comment for documentation
COMMENT ON TABLE video_summaries IS 'Stores frame transcription/OCR and LLM summary of processed video files';
