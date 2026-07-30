CREATE TABLE chat_analysis_task
(
    id            UUID        NOT NULL PRIMARY KEY,
    status        VARCHAR(20) NOT NULL,
    result        TEXT,
    error_message TEXT,
    created_at    TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ
);

-- 创建索引以加速状态轮询查询
CREATE INDEX idx_chat_analysis_task_status ON chat_analysis_task (status);