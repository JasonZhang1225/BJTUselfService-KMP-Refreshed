-- 最近一次真正领取任务的轮询，与上传快照的设备联系时间分开记录。
ALTER TABLE devices ADD COLUMN last_poll_at INTEGER;
--> statement-breakpoint
ALTER TABLE devices ADD COLUMN poll_interval_seconds INTEGER;
--> statement-breakpoint
INSERT OR REPLACE INTO template_meta (key, value) VALUES ('schema_migration', '0004');
