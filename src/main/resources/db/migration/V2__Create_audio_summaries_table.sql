-- Create audio_summaries table
CREATE TABLE IF NOT EXISTS audio_summaries
(
    id
    VARCHAR
(
    255
) PRIMARY KEY,
    file_hash VARCHAR
(
    255
) NOT NULL,
    file_path TEXT NOT NULL,
    transcript TEXT,
    summary TEXT,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT CURRENT_TIMESTAMP NOT NULL
    );

-- Add unique constraint on file_hash to prevent duplicate processing
ALTER TABLE audio_summaries
    ADD CONSTRAINT uq_audio_summaries_file_hash UNIQUE (file_hash);

-- Add an index on file_hash for faster cache-miss lookups
CREATE INDEX IF NOT EXISTS idx_audio_summaries_file_hash ON audio_summaries (file_hash);

-- Add a comment for documentation
COMMENT
ON TABLE audio_summaries IS 'Stores the transcription and LLM summary of processed audio files';