-- 模型配置新增字段: 调用前延迟秒数（降低服务区压力）
ALTER TABLE ai_model_configs ADD COLUMN pre_call_delay_seconds INT NOT NULL DEFAULT 0;
