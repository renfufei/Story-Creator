-- 章节 ↔ 分卷 显式绑定（2026-09-17）
--
-- 背景：分卷此前完全靠 projects.chapters_per_volume 整除推算：
--       volumeNumber = (chapterNumber - 1) / chaptersPerVolume + 1
-- 而这个数字本质是「自动创作时」的默认值，用户无法手工微调卷边界
-- （例如「第 2 卷再加 5 章」「把这个边缘章节挪到另一卷」）。
--
-- 方案（兼容优先）：
--   1. chapters.volume_id —— 章节到 volume_outlines.id 的显式外键，可空。
--      复用已有的 volume_outlines 作为「分卷」实体：它本来就是一卷一条
--      （卷号 + 标题 + 弧名 + 弧线 + 章范围）。
--   2. projects.volume_binding_enabled —— 是否启用显式绑定：
--        FALSE（默认值，所有存量数据）→ 一切照旧用整除推算，老项目行为零变化；
--        TRUE（用户在本管理页做过任意一次调整）→ 以 volume_id 为准，
--        个别未绑定的章节仍按原公式兜底，不会「章节凭空消失」。
--
-- 安全边界：volume_id ON DELETE SET NULL —— 分卷被删除时章节自动解绑并回落旧逻辑，
-- 不会出现孤儿外键或章节丢失。

ALTER TABLE chapters ADD COLUMN volume_id BIGINT;

ALTER TABLE chapters
    ADD CONSTRAINT fk_chapters_volume
    FOREIGN KEY (volume_id) REFERENCES volume_outlines(id) ON DELETE SET NULL;

CREATE INDEX idx_chapters_volume_id ON chapters(volume_id);

ALTER TABLE projects ADD COLUMN volume_binding_enabled BOOLEAN NOT NULL DEFAULT FALSE;
