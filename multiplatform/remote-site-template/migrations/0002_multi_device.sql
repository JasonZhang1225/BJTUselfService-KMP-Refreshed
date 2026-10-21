-- 模板 0.4.0：一个 Site 可同时绑定电脑和手机。
-- desktop：常驻、轮询即时任务；mobile：只在 App 刷新后上传快照，不领取任务。
ALTER TABLE devices ADD COLUMN device_kind TEXT NOT NULL DEFAULT 'desktop';
INSERT OR REPLACE INTO template_meta (key, value) VALUES ('schema_migration', '0002');
