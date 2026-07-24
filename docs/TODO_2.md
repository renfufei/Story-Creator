# TODO

## 待办事项

---

### 2. 分卷角色动态生成（Volume-Driven Character Introduction）

#### 问题

当前角色生成发生在 `CHARACTER_DESIGN` 阶段，一次性生成所有角色（N 张角色卡）。这导致：

1. **前期角色过载**：所有角色在故事开始前就已设计完毕，但读者不需要在第一章就认识全部角色
2. **角色与剧情脱节**：角色设计时尚无分卷大纲，无法针对具体卷的故事线设计针对性角色
3. **缺少登场节奏控制**：`chapter_outlines.character_names` 虽标注了出场角色，但AI在生成大纲时只能从已有角色中选择，无法根据剧情需要"创造"新角色
4. **角色同质化风险**：一次性设计多个角色时，即使有去重上下文，仍容易在功能定位上重复

#### 核心理念

> **角色服务于故事，而非故事迁就角色。** 每卷的故事线确定后，再根据剧情需求动态引入新角色，让角色的出现有明确的叙事理由和时机。

#### 设计方案：分阶段角色引入

将角色生成拆分为两个阶段：

##### 阶段一：初始角色（现有流程，保持不变）

在 `CHARACTER_DESIGN` 步骤中生成**核心角色**（主角 + 核心配角），通常 2-4 人：
- 主角（1-2人）：贯穿全书的绝对核心
- 核心配角（1-2人）：与主角有深度羁绊、全程陪伴的角色

这些角色从第一卷就登场，是故事的基石。

##### 阶段二：补充角色（新增子步骤 `VOLUME_CHARACTERS`）

在**分卷大纲生成之后、章节大纲生成之前**，为每卷动态生成补充角色：

```
OUTLINE_GENERATION step (改造后)
  ├── Phase 1: VOLUME_ARC × numVolumes          ← 现有
  ├── Phase 1.5: VOLUME_CHARACTERS × numVolumes  ← 【新增】
  ├── Phase 2: CHAPTER_OUTLINE × numChapters     ← 现有（可引用新角色）
  ├── Phase 2.5: CHAPTER_OUTLINE_REFINE          ← 现有
  └── Phase 3: STORY_SUMMARY                     ← 现有
```

**每卷生成 1-3 个补充角色**，AI 基于以下上下文决定：
- 本卷故事弧（`volume_outlines.arc_summary`）
- 已有全部角色（避免功能重复）
- 前几卷已引入的补充角色（避免角色膨胀）
- 本卷的叙事需求（需要什么类型的角色来推动剧情）

##### 2.1 补充角色的关键属性

每个补充角色除了标准角色卡字段外，还需包含：

| 属性 | 说明 |
|---|---|
| `introduction_volume` | 首次登场的卷号 |
| `introduction_chapter` | 建议首次登场的章节号（由AI根据剧情节奏建议） |
| `introduction_context` | 登场情境说明（如"在主角被围困时以援军身份出现"） |
| `narrative_role` | 本卷中的叙事功能（如"对手"、"导师"、"信息源"、"牺牲者"） |
| `lifespan` | 角色活跃范围：`VOLUME_LOCAL`（仅本卷）/ `RECURRING`（后续卷可能再出现）/ `PERMANENT`（加入核心团队） |

##### 2.2 与章节大纲的衔接

Phase 1.5 完成后，Phase 2（章节大纲生成）的上下文中将包含：
- 初始角色列表（全部）
- 本卷补充角色列表（含 `introduction_chapter` 和 `introduction_context`）

AI 在生成 `chapter_outlines.character_names` 时：
- 可以引用补充角色的名字
- 应尊重 `introduction_chapter` 的建议（不在建议章节之前安排该角色登场）
- 在登场章节的大纲中自然融入 `introduction_context` 描述的情境

##### 2.3 数据模型变更

扩展 `characters` 表（或新增字段）：

```sql
ALTER TABLE characters ADD COLUMN character_type VARCHAR(20) DEFAULT 'INITIAL';
  -- INITIAL: 初始角色（CHARACTER_DESIGN 阶段生成）
  -- SUPPLEMENTARY: 补充角色（VOLUME_CHARACTERS 阶段生成）

ALTER TABLE characters ADD COLUMN introduction_volume INT;
ALTER TABLE characters ADD COLUMN introduction_chapter INT;
ALTER TABLE characters ADD COLUMN introduction_context TEXT;
ALTER TABLE characters ADD COLUMN narrative_role VARCHAR(100);
ALTER TABLE characters ADD COLUMN lifespan VARCHAR(20) DEFAULT 'RECURRING';
  -- VOLUME_LOCAL / RECURRING / PERMANENT
```

##### 2.4 生成策略控制

- **数量控制**：每卷 1-3 个补充角色，由 AI 根据故事复杂度自行判断
  - 第一卷通常 1-2 个（故事刚展开，不宜过多）
  - 中间卷 2-3 个（剧情复杂化，需要更多角色支撑）
  - 最终卷 0-1 个（收束阶段，避免引入新线索）
- **累计上限**：可在项目设置中配置补充角色总数上限（如 `maxSupplementaryCharacters`，默认 15）
- **跳过条件**：如果某卷的故事弧足够简单（由 AI 判断），可以不生成新角色
- **用户干预**：生成后用户可编辑/删除补充角色，再继续章节大纲生成

##### 2.5 提示词模板设计（`VOLUME_CHARACTERS` 子步骤）

```yaml
sub_step: VOLUME_CHARACTERS
variables:
  - worldSetting (或对应 facet)
  - volumeArc           # 本卷故事弧
  - existingCharacters  # 所有已有角色摘要（初始 + 前几卷补充）
  - volumeNumber
  - totalVolumes
  - previousVolumeCharacters  # 前几卷补充角色列表（防膨胀）

template: |
  你正在为一部网络小说的第{{volumeNumber}}卷（共{{totalVolumes}}卷）设计补充角色。

  【本卷故事线】
  {{volumeArc}}

  【已有角色】
  {{existingCharacters}}

  【前几卷引入的补充角色】
  {{previousVolumeCharacters}}

  请根据本卷故事线的需要，设计 1-3 个新的补充角色。要求：
  1. 每个角色必须有明确的叙事功能（推动本卷某条剧情线）
  2. 不得与已有角色在功能定位上重复
  3. 明确指定该角色首次登场的章节（在本卷范围内）和登场情境
  4. 标注角色活跃范围：仅本卷（VOLUME_LOCAL）/ 后续可能再出现（RECURRING）/ 加入核心团队（PERMANENT）
  5. 如果本卷故事线足够简单，不需要新角色，可以输出"本卷无需补充角色"

  对每个角色请输出：
  【角色名】...
  【性别】...
  【身份/职业】...
  【性格特点】...
  【外貌特征】简述
  【叙事功能】在本卷中的作用
  【首次登场章节】第X章
  【登场情境】描述该角色出现的具体场景
  【活跃范围】VOLUME_LOCAL / RECURRING / PERMANENT
  【与主角关系】...
```

##### 2.6 AutoRun 集成

在 `DefaultAutoRunStrategy`（和 `EnhancedAutoRunStrategy`）的 `OUTLINE_GENERATION` 执行流程中：
- Phase 1（VOLUME_ARC）完成后，自动执行 Phase 1.5（VOLUME_CHARACTERS）
- Phase 1.5 完成后，再进入 Phase 2（CHAPTER_OUTLINE）
- 用户可通过 `AutoRunStepConfig` 控制是否启用此步骤（默认启用）

##### 2.7 前端呈现

- Workflow 页面的角色区域按类型分组显示：
  - **核心角色**（初始）：顶部，始终显示
  - **第 N 卷补充角色**：按卷折叠显示，标注登场章节和叙事功能
- 角色卡片上增加标签：`核心`、`第2卷引入`、`仅本卷` 等
- 支持手动将 `VOLUME_LOCAL` 角色提升为 `RECURRING`（如用户觉得该角色后续还有用）

##### 2.8 兼容性考虑

- 现有项目不受影响：`character_type` 默认为 `INITIAL`，无补充角色时流程与现在完全一致
- 新项目可在项目设置中开启/关闭"分卷角色动态生成"功能
- 关闭时跳过 Phase 1.5，行为与当前版本一致
- 已有角色数据无需迁移，默认标记为 `INITIAL` 即可

---
