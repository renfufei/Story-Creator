-- 修正内置章节分割配置的优先级：精确的章节号匹配必须优先于启发式匹配。
--
-- 历史数据里「独立标题行」「分隔线」排在了「卷章格式」「中文数字章节号」之前，而调度器取
-- 「首个命中的 Handler」，于是一条正文顶部的「-------」被分隔线命中后就地胜出，把整本书
-- 合成了一章（真实案例：1 条分隔线压掉了 6 个「第X章」）。
--
-- 目标顺序与各 Handler 的 @Order 意图一致：
--   10 卷章格式 / 20 阿拉伯数字章节号 / 30 中文数字章节号 / 40 独立标题行 / 50 分隔线
-- 自定义正则配置由业务代码固定为 100，仍排在内置配置之后。
UPDATE chapter_split_configs SET sort_order = 10 WHERE is_builtin = TRUE AND name = '卷章格式';
UPDATE chapter_split_configs SET sort_order = 20 WHERE is_builtin = TRUE AND name = '阿拉伯数字章节号';
UPDATE chapter_split_configs SET sort_order = 30 WHERE is_builtin = TRUE AND name = '中文数字章节号';
UPDATE chapter_split_configs SET sort_order = 40 WHERE is_builtin = TRUE AND name = '独立标题行';
UPDATE chapter_split_configs SET sort_order = 50 WHERE is_builtin = TRUE AND name = '分隔线';
