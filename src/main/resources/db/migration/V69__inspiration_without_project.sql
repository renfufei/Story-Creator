-- 灵感允许「无项目」：project_id = 0 是「未归属任何项目」的哨兵值。
--
-- 为什么必须重建整张表：V66 建的 inspirations 上带着匿名外键
--     FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE
-- 而 H2 **删不掉匿名约束**（V60 的注释里已经踩过同一个坑：tts_export_tasks / chat_messages
-- 当年也是这么重建的），所以只能「关掉引用完整性 → 建备份表 → 重建 → 回填 → 换回新表」。
--
-- ⚠️ 两条不能省的细节（2026-10-08 在 H2 2.2.224 上实测过）：
--   1) 回填时带 id 显式插入，H2 的 IDENTITY 序列**不会**跟着前进：重建后的新表序列从 1 开始，
--      第一次自增插入就会撞历史主键（Unique index or primary key violation）。
--      所以末尾必须把序列顶到 max(id)+1。V60 漏了这一步 —— 那几张表当年若已有数据，
--      之后再新建 tts_export_tasks / chat_sessions 都会撞主键。
--   2) 外键没了，删项目**不再级联**删灵感 ⇒ 删除项目的两处代码必须自己清：
--      ProjectEditApiController.delete 与 ImportService.deleteAllProjectData。
SET REFERENTIAL_INTEGRITY FALSE;

CREATE TABLE inspirations_bak AS SELECT * FROM inspirations;
DROP TABLE inspirations;

CREATE TABLE inspirations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 0 = 不属于任何项目（「所有灵感」页可以直接新建这种「还没归到项目里」的灵感）
    project_id  BIGINT NOT NULL DEFAULT 0,
    title       VARCHAR(200) NOT NULL,
    content     TEXT,
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_inspirations_project ON inspirations(project_id);

INSERT INTO inspirations (id, project_id, title, content, created_at, updated_at)
    SELECT id, project_id, title, content, created_at, updated_at FROM inspirations_bak;

DROP TABLE inspirations_bak;

-- 见上面的注意 1)
ALTER TABLE inspirations ALTER COLUMN id RESTART WITH (SELECT COALESCE(MAX(id), 0) + 1 FROM inspirations);

SET REFERENTIAL_INTEGRITY TRUE;
