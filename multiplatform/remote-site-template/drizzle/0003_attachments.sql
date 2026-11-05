-- 老师附件：不超过 5 MiB，按 1 MiB 分块存（D1 单行放不下整个文件）。
-- 每行都属于一台设备：各设备只管理自己上传的附件，互不跟踪。
CREATE TABLE IF NOT EXISTS attachments (
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    attachment_key TEXT NOT NULL,
    assignment_id TEXT NOT NULL,
    attachment_id TEXT NOT NULL,
    name TEXT NOT NULL,
    content_type TEXT NOT NULL,
    size_bytes INTEGER NOT NULL,
    chunk_count INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (device_id, attachment_key)
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS attachment_chunks (
    device_id TEXT NOT NULL,
    attachment_key TEXT NOT NULL,
    chunk_index INTEGER NOT NULL,
    data BLOB NOT NULL,
    PRIMARY KEY (device_id, attachment_key, chunk_index),
    FOREIGN KEY (device_id, attachment_key) REFERENCES attachments(device_id, attachment_key) ON DELETE CASCADE
);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS attachments_lookup ON attachments (assignment_id, attachment_id);
--> statement-breakpoint
INSERT OR REPLACE INTO template_meta (key, value) VALUES ('schema_migration', '0003');
