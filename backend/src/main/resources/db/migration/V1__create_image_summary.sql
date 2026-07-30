CREATE TABLE IF NOT EXISTS image_summary
(
    id
    VARCHAR
(
    255
) PRIMARY KEY,
    image_hash VARCHAR
(
    255
) NOT NULL,
    file_path TEXT NOT NULL,
    summary TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
    );

CREATE UNIQUE INDEX IF NOT EXISTS uk_image_hash ON image_summary (image_hash);