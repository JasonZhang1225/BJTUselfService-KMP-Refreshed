-- 交大自由行远程模板：设备绑定、配对码、最新快照与即时任务。
-- 只追加迁移，不修改已发布文件；升级用 0002_*.sql 等增量迁移。
-- 所有凭据只保存 SHA-256 摘要；不保存学校密码、Cookie、令牌或数据库密钥。

CREATE TABLE IF NOT EXISTS template_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
--> statement-breakpoint
-- 拥有者生成的一次性配对码：短期有效、限制尝试次数，成功后作废。
CREATE TABLE IF NOT EXISTS pairing_codes (
    code_hash TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    used_at INTEGER
);
--> statement-breakpoint
-- 一次配对 = 一个设备行 = 一个绑定代次。重新配对得到新 device_id，旧令牌与旧序号全部失效。
CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    token_hash TEXT NOT NULL UNIQUE,
    device_name TEXT NOT NULL,
    account_binding_id TEXT NOT NULL,
    protocol_version INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    revoked_at INTEGER,
    -- 最近在线：搭载在轮询上，最多约每分钟写一次，不为每次空轮询写库。
    last_seen_at INTEGER,
    -- 设备上报的数据范围（JSON 数组）；Site 按最近一次收到的范围拦截查询。
    scopes_json TEXT NOT NULL DEFAULT '[]',
    scopes_revision INTEGER NOT NULL DEFAULT 0
);
--> statement-breakpoint
-- 每个设备只保留最新完整快照；sequence 单调递增，旧上传不能覆盖新数据。
CREATE TABLE IF NOT EXISTS snapshots (
    device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    snapshot_id TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    schema_version INTEGER NOT NULL,
    generated_at INTEGER NOT NULL,
    uploaded_at INTEGER NOT NULL,
    received_at INTEGER NOT NULL,
    body_json TEXT NOT NULL
);
--> statement-breakpoint
CREATE TABLE IF NOT EXISTS tasks (
    request_id TEXT PRIMARY KEY,
    idempotency_key TEXT NOT NULL,
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    account_binding_id TEXT NOT NULL,
    operation TEXT NOT NULL,
    arguments_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    -- queued → running → succeeded/partial/failed；未领取或租约耗尽为 expired；撤销绑定为 revoked。
    status TEXT NOT NULL,
    lease_id TEXT,
    lease_until INTEGER,
    attempt INTEGER NOT NULL DEFAULT 0,
    completed_at INTEGER,
    result_json TEXT,
    result_cleared_at INTEGER,
    UNIQUE (device_id, idempotency_key)
);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS tasks_claim ON tasks (device_id, status, created_at);
--> statement-breakpoint
CREATE INDEX IF NOT EXISTS tasks_cleanup ON tasks (created_at);
