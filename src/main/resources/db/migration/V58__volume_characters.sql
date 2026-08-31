-- 角色表新增字段: 角色类型和活跃卷范围
ALTER TABLE characters ADD COLUMN character_type VARCHAR(20) DEFAULT 'INITIAL';
ALTER TABLE characters ADD COLUMN start_volume INT;
ALTER TABLE characters ADD COLUMN end_volume INT;

-- 项目表新增配置字段: 分卷角色引入速率
ALTER TABLE projects ADD COLUMN recurring_character_rate DOUBLE DEFAULT 0.5;
ALTER TABLE projects ADD COLUMN temp_character_rate DOUBLE DEFAULT 3.0;
