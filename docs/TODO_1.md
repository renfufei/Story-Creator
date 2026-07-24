# TODO

## 待办事项

### 1. 世界观分面展开（Faceted World Elaboration）

#### 问题

项目生成的【世界观设定】内容可能很长（6大板块：世界背景、力量体系、势力分布、历史脉络、特色元素、冲突根源），导致后续步骤的提示词占用大量 token。

当前的处理方式：
- `ContextSummaryService.summarizeWorldSetting()` 只在保存时生成一份**通用摘要**（300-500字）
- `WorkflowContextBuilder` 在 `chapterNumber > 0` 时自动使用摘要，否则使用完整版
- 各服务手动 `truncate()` 到 300-600 字不等——**信息丢失严重且不可控**

核心矛盾：**不同模板需要世界观的不同面（facet）**，而非统一的"缩短版"。例如：
- 【角色状态】中 `CULTIVATION_LEVEL`（修为境界）维度需要**力量体系**的等级划分细节
- 【角色卡生成】需要知道世界的**势力分布**和**力量体系**来设计角色背景
- 【章节写作】需要**世界背景**和**特色元素**来营造氛围
- 【事件计划】需要**冲突根源**和**势力分布**来推演剧情

#### 核心理念：不只是浓缩，更是面向用途的展开与细化

分面处理**不等于压缩**。世界观原文可能对某些方面只用了一两句话带过（如"修炼分为九个境界"），但对下游模板来说这远远不够。分面的本质是：

> **以完整世界观为素材，针对特定用途进行 AI 重组、补全和细化。**

例如 `POWER_SYSTEM` 面，AI 应该：
- 列出每个境界的名称、特征能力、与相邻境界的核心区别
- 补充境界突破的条件或标志性表现
- 明确战力梯度（如"金丹期可御剑飞行，元婴期可神识外放"）
- 整理特殊能力/禁忌（如"渡劫期不可轻易出手，否则引天劫"）

这样后续模板拿到的不是模糊的"九个境界"，而是一份可直接参照的**力量体系速查手册**。

#### 设计方案：分面展开

将世界观按主题拆分为多个**切面（Facet）**，每个面针对特定用途进行**提取、重组、细化**，后续模板按需引用。

##### 1.1 世界观面（World Facets）定义

| Facet Key | 名称 | 来源板块 | 目标字数 | 主要消费者 |
|---|---|---|---|---|
| `POWER_SYSTEM` | 力量体系浓缩 | 力量体系 | 600-1000字 | CHARACTER_STATES（境界维度）、CHARACTER_CARD、CHARACTER_REFINE、CHARACTER_BEHAVIOR_BOUNDARIES |
| `WORLD_BACKGROUND` | 世界背景浓缩 | 世界背景 + 特色元素 | 600-800字 | CHAPTER_WRITING_PRIMARY、CHAPTER_OUTLINE、VOLUME_ARC |
| `FACTION_MAP` | 势力格局浓缩 | 势力分布 | 400-600字 | CHARACTER_CARD、CHAPTER_EVENT_PLAN、VOLUME_ARC |
| `CONFLICT_ROOTS` | 冲突根源浓缩 | 冲突根源 + 历史脉络 | 400-600字 | CHAPTER_EVENT_PLAN、VOLUME_ARC、CHAPTER_PLOT_REASONING |
| `GENERAL` | 通用摘要（现有） | 全文 | 300-500字 | 兜底/未指定面的场景 |

> **字数设计原则**：分面展开不同于通用摘要——其目的是**以世界观为种子，面向特定用途进行细化和补全**。`POWER_SYSTEM` 给最多字数是因为需要逐级列出境界名称、特征能力、突破条件、战力区分等细节（原文可能只提了名称）；`WORLD_BACKGROUND` 需要展开具体意象以支持写作氛围；后两者偏关系/结构，信息可以更紧凑但仍需明确各方立场和矛盾线索。

##### 1.2 生成时机

在世界观保存时（`WorkflowStateService.saveWorldSetting()`）自动生成所有 facet，类似当前的 `summarizeWorldSetting()` 逻辑：

```
保存完整世界观
  ├─ 生成 GENERAL 通用摘要（现有逻辑，保持兼容）
  ├─ 展开 POWER_SYSTEM — 力量体系速查手册
  ├─ 展开 WORLD_BACKGROUND — 世界氛围指南
  ├─ 展开 FACTION_MAP — 势力关系图谱
  └─ 展开 CONFLICT_ROOTS — 冲突矛盾索引
```

> 注：展开过程中 AI 会基于世界观原文进行合理的细化补全，因此 facet 内容可能**比原文对应段落更详细**——这是预期行为。

每个 facet 使用独立的展开模板（`world-facets/POWER_SYSTEM.yaml` 等），指示 AI 从完整世界观中**提取该面相关内容，并进行结构化重组和细化补全**。

模板应引导 AI：
- **提取**：从原文中找出与该面相关的所有信息
- **重组**：按该面的用途重新组织结构（如力量体系按等级从低到高排列）
- **细化**：对原文中一笔带过的内容进行合理推演和补充（如"九个境界"→列出每个境界的名称和特征）
- **约束**：补充的内容必须与原文逻辑自洽，不得引入矛盾设定

##### 1.3 存储设计

方案 A：扩展 `world_settings` 表新增列（简单但不灵活）：
```sql
ALTER TABLE world_settings ADD COLUMN facet_power_system TEXT;
ALTER TABLE world_settings ADD COLUMN facet_world_background TEXT;
ALTER TABLE world_settings ADD COLUMN facet_faction_map TEXT;
ALTER TABLE world_settings ADD COLUMN facet_conflict_roots TEXT;
```

方案 B（推荐）：新建 `world_setting_facets` 表：
```sql
CREATE TABLE world_setting_facets (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    facet_key VARCHAR(50) NOT NULL,     -- 'POWER_SYSTEM', 'WORLD_BACKGROUND', etc.
    content TEXT,
    content_hash VARCHAR(64),           -- SHA-256，用于缓存判断是否需要重新生成
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    UNIQUE(project_id, facet_key)
);
```

优点：facet 可扩展（未来新增 facet 无需加列），支持缓存哈希避免重复调用 AI。

##### 1.4 模板变量改造

各模板中 `{{worldSetting}}` 变量按消费需求替换为对应 facet：

| 模板 | 当前变量 | 改造后 |
|---|---|---|
| CHARACTER_CARD | `{{worldSetting}}`（全文） | `{{worldPowerSystem}}` + `{{worldFactionMap}}` |
| CHARACTER_REFINE | `{{worldSetting}}`（truncate 600） | `{{worldPowerSystem}}` |
| CHARACTER_BEHAVIOR_BOUNDARIES | `{{worldSetting}}` | `{{worldPowerSystem}}` + `{{worldBackground}}` |
| VOLUME_ARC | `{{worldSetting}}`（truncate 400） | `{{worldBackground}}` + `{{worldConflictRoots}}` |
| CHAPTER_OUTLINE | `{{worldSetting}}`（truncate 300） | `{{worldBackground}}` |
| CHAPTER_EVENT_PLAN | `{{worldSetting}}` | `{{worldConflictRoots}}` + `{{worldFactionMap}}` |
| CHAPTER_WRITING_PRIMARY | `{{worldSetting}}`（summary） | `{{worldBackground}}` |
| WRITING_RULES | `{{worldSetting}}` | `{{worldSetting}}`（保持全文，因为规则需要完整上下文） |
| STYLE_FINGERPRINT | `{{worldSetting}}` | `{{worldSetting}}`（保持全文） |

> **注意**：WRITING_RULES 和 STYLE_FINGERPRINT 在世界观生成阶段紧随其后执行，此时完整世界观刚生成，直接使用全文即可（这两个模板本身就是对世界观的"派生加工"）。

##### 1.5 角色状态与力量体系的关联

当前 `CHARACTER_STATES` 模板的 `{{dimList}}` 变量列出启用的维度名称（如"修为境界、宝物/装备、地理位置"），但模板中**没有世界观上下文**——AI 只能凭章节内容猜测境界名称。

改造方案：
- 为 `CHARACTER_STATES` 模板新增 `{{worldPowerSystem}}` 变量
- 当 `CULTIVATION_LEVEL` 维度启用时，注入力量体系浓缩面，让 AI 准确识别境界等级变化
- 模板改为：

```yaml
template: |
  请根据以下章节内容，汇总本章结束时各出场角色的当前状态。
  包括：{{dimList}}等。

  {{worldPowerSystem}}
  {{charNames}}
  {{prevStates}}
  【章节内容】
  {{chapterExcerpt}}
```

其中 `{{worldPowerSystem}}` 在 `CULTIVATION_LEVEL` 维度启用时注入：
```
【力量体系参考】
<POWER_SYSTEM facet 内容>
```
未启用时为空字符串。

##### 1.6 实现步骤

1. **V49 迁移**：创建 `world_setting_facets` 表
2. **领域层**：新增 `WorldFacetKey` 枚举 + `WorldSettingFacetEntity`
3. **展开模板**：新增 4 个 YAML 模板到 `src/main/resources/prompts/world-facets/`
4. **展开服务**：`WorldFacetElaborationService`，在世界观保存时批量生成各 facet
5. **上下文构建**：`WorkflowContextBuilder` 加载 facet，供模板变量使用
6. **模板变量适配**：各 service 的 `buildXxxVariables()` 方法使用 facet 替代 truncate
7. **CHARACTER_STATES 改造**：注入 `worldPowerSystem`
8. **兼容处理**：facet 为空时 fallback 到现有 truncate 逻辑（渐进式迁移）

##### 1.7 各 Facet 展开模板设计示意

**POWER_SYSTEM.yaml** — 力量体系速查手册：
```
请基于以下世界观设定，生成一份【力量体系速查手册】（600-1000字）。

要求：
1. 逐级列出所有境界/等级的名称，从低到高排列
2. 每个境界注明：特征能力、核心标志、与上下相邻境界的关键区别
3. 说明突破/晋级的条件或常见方式
4. 列出特殊规则或禁忌（如有）
5. 如原文对某些境界描述不足，可基于整体逻辑合理补充，但不得与原文矛盾

输出格式：结构化纯文本，不用Markdown标记。按等级从低到高依次列出。
```

**WORLD_BACKGROUND.yaml** — 世界氛围指南：
```
请基于以下世界观设定，生成一份【世界氛围指南】（600-800字）。

要求：
1. 概述时代背景和社会结构
2. 描述关键地理环境和标志性场所的氛围特征
3. 列出独特的文化习俗、物种或规则
4. 提供感官细节参考（常见景象、声音、气味等）
5. 适合直接作为写作时的氛围参照

输出格式：按场景/区域分段，每段包含具体的感官和氛围描写要素。
```

**FACTION_MAP.yaml** — 势力关系图谱：
```
请基于以下世界观设定，生成一份【势力关系图谱】（400-600字）。

要求：
1. 列出所有主要势力/组织/国家
2. 每个势力注明：核心特征、实力定位、关键人物（如有）
3. 明确势力间的关系（同盟/敌对/从属/暗中博弈）
4. 标注当前的力量格局平衡点

输出格式：先列势力清单，再描述关系网络。
```

**CONFLICT_ROOTS.yaml** — 冲突矛盾索引：
```
请基于以下世界观设定，生成一份【冲突矛盾索引】（400-600字）。

要求：
1. 列出世界中存在的主要矛盾线（宏观到微观）
2. 每条矛盾线注明：对立双方、根源、当前状态、可能的爆发点
3. 标注矛盾间的关联关系（某条矛盾是否会触发另一条）
4. 提供可供剧情利用的冲突触发点建议

输出格式：按矛盾规模从大到小排列，每条包含对立方、根源、状态。
```

##### 1.7 兼容性考虑

- 现有项目已有 `world_settings.summary`，保持不变（`GENERAL` facet 与之等价）
- 已有项目首次触发时可手动"重新浓缩"（提供一键操作按钮）
- 自定义模板（DB 中 isDefault=true）如果仍使用 `{{worldSetting}}`，保持现有行为不变
- 新增的 `{{worldPowerSystem}}` 等变量仅在 builtin 模板中使用，用户自定义模板可选择性采用

---
