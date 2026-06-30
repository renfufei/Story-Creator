# 全自动创作工作流改进方案

## 1. 改进目标与设计理念

### 1.1 要解决的核心问题

| 问题 | 表现 | 影响 |
|------|------|------|
| 长篇文风漂移 | 50 章后文字风格与开头明显不同 | 读者体验断裂 |
| 伏笔遗忘/矛盾 | 前文埋下的线索后文无人接 | 剧情逻辑硬伤 |
| 角色 OOC | 配角在后期突然性格转变无铺垫 | 人物塑造崩坏 |
| 章间衔接断裂 | 上一章结尾是黄昏，下一章突然变成清晨 | 连续性崩溃 |
| 修复成本高 | 全书写完才校对，发现问题要回溯大量上下文 | 修复质量差，效率低 |
| 情节同质化 | 缺少对冲突/转折的预先推演，依赖 AI 自由发挥 | 套路重复 |

### 1.2 核心设计理念

**"结构化规则约束 + 循环式精细生成 + 即时反馈修正"**

- **规则先行**：在创作开始前建立写作规则文档，约束 AI 行为边界
- **循环式生成**：每章写作不是单次调用，而是"上下文梳理→推演→生成→审查→修正→追踪更新"的精细循环
- **即时反馈**：不等全书写完再审，每章写完即检、每 N 章深度审查，降低错误累积
- **上下文追踪**：逐章维护伏笔表、角色状态、关系网络，确保 AI 始终"记得"全局

### 1.3 与现有架构的关系

保留当前 6 步宏观流水线不变（它在架构层面是合理的），改进集中在：
1. 新增"创作准备"前置步骤（Step 0）
2. **大幅强化"章节写作"步骤**：从单次生成升级为 7 步精细循环
3. 增加"周期性审查"机制嵌入写作流程中
4. 强化润色步骤（风格一致性校验）

---

## 2. 改进后的工作流总览

### 2.1 宏观流水线（7 步）

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          全自动创作改进版工作流                                    │
├─────────────────────────────────────────────────────────────────────────────────┤
│                                                                                 │
│  Step 0        Step 1        Step 2        Step 3                               │
│  创作准备  →   世界观设定  →  角色设计   →  大纲生成                               │
│  (新增)        (增强)        (增强)        (增强)                                 │
│                                                                                 │
│          ┌──────────────────────────────────────────────┐                       │
│          │           Step 4: 章节写作（重大改进）          │                       │
│          │                                              │                       │
│          │  ┌─────────────────────────────────────────┐ │                       │
│          │  │        每章 7 步精细创作循环              │ │                       │
│          │  │                                         │ │                       │
│          │  │  ① 前文梳理 → ② 剧情推演 → ③ 正文生成  │ │                       │
│          │  │       ↑                           ↓     │ │                       │
│          │  │  ⑥ 故事线更新 ← ⑤ 内容优化 ← ④ 即时审查│ │                       │
│          │  │                                         │ │                       │
│          │  │          ⑦ 周期性深度审查                │ │                       │
│          │  │           (每 N 章触发)                  │ │                       │
│          │  └─────────────────────────────────────────┘ │                       │
│          └──────────────────────────────────────────────┘                       │
│                                                                                 │
│          Step 5          Step 6                                                  │
│          润色修改    →   校对精修                                                  │
│          (增强)          (保持)                                                   │
│                                                                                 │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 关键变化对照

| 步骤 | 当前实现 | 改进后 | 变化程度 |
|------|----------|--------|----------|
| 创作准备 | 无 | 新增：规则文档 + 风格样本 + 红线设定 | **新增** |
| 世界观设定 | 单次生成 | 增加结构化输出约束 | 轻微增强 |
| 角色设计 | 卡片生成 + 精修 | 增加"行为边界"字段 | 轻微增强 |
| 大纲生成 | 4 阶段流水线 | 增加"每章事件清单"子步骤 | 中度增强 |
| 章节写作 | 单次生成 + 角色状态 | 7 步精细循环 | **重大改进** |
| 润色修改 | 逐章润色 | 增加风格一致性校验 | 中度增强 |
| 校对精修 | 5 维检查 + 修复 | 保持现有，增加全书级审查 | 轻微增强 |

---

## 3. 各步骤详细设计

### 3.0 创作准备（新增前置步骤）

**目的**：在任何内容生成之前，建立整部小说的"宪法"——写作规则文档，所有后续 AI 调用都将受此约束。

#### 3.0.1 写作规则文档生成

基于用户输入的标题、类型、简介，AI 生成一份结构化的写作规则文档：

```yaml
# 写作规则文档结构
writing_rules:
  narrative_perspective: "第三人称有限视角"     # 叙事视角
  tense: "过去时"                              # 时态
  chapter_structure:                           # 章节结构要求
    target_word_count: 3000                    # 目标字数
    scene_count: "2-4"                         # 每章场景数
    must_include: ["至少一个冲突点", "章末悬念"] # 结构要求
  style_constraints:                           # 风格约束
    vocabulary_level: "中等偏上"                # 词汇水平
    sentence_rhythm: "长短交替，避免连续3句以上短句" # 句式节奏
    dialogue_ratio: "30%-50%"                  # 对话占比
    description_style: "白描为主，适度修辞"     # 描写风格
  taboos:                                      # 禁忌/红线
    - "不得出现现代网络用语"
    - "不得出现第四面墙突破"
    - "角色不得突然获得未铺垫的能力"
    - "避免大段心理独白超过200字"
  pacing_rules:                                # 节奏规则
    tension_curve: "每3章一个小高潮，每10章一个大高潮"
    rest_chapters: "高潮后必须有1-2章缓冲"
```

#### 3.0.2 风格样本锁定

如果用户提供了参考文风的文本片段（或者系统在世界观生成后提取风格特征），生成一份"风格指纹"：

- 用词偏好（高频词汇列表）
- 句式特征（平均句长、分句数）
- 修辞手法倾向
- 对话标点习惯

**后续所有生成步骤的 prompt 都将注入写作规则文档和风格指纹**，作为硬性约束。

#### 3.0.3 实现要点

| 项目 | 方案 |
|------|------|
| 数据存储 | 新增 `writing_rules` 表（project_id, rule_type, content） |
| PromptSubStep | `WRITING_RULES_GENERATION`, `STYLE_ANALYSIS` |
| 模型参数 | maxTokens=2048, temperature=0.5 |
| 断点 | 规则文档完成即可跳过 |
| 步骤位序 | WorkflowStep 新增 `PREPARATION`，order=0 |

---

### 3.1 世界观设定（增强）

**保持现有单次生成流程**，增加以下改进：

#### 增强点：结构化输出要求

在 prompt 中要求 AI 按固定结构输出世界观：

```
一、时代背景与社会形态
二、地理环境与势力分布
三、力量体系/魔法体系/科技体系
四、核心规则与限制（世界运行的"硬规则"）
五、文化习俗与禁忌
六、核心矛盾与暗涌冲突
```

**为什么这样做**：结构化输出确保后续步骤引用世界观时可以精确定位相关部分，而非把整块文字丢给 AI。

---

### 3.2 角色设计（增强）

**保持现有三阶段流程**（逐卡生成→总览→精修），增加以下改进：

#### 增强点：角色行为边界卡

在角色卡精修阶段，额外生成一个"行为边界"字段：

```yaml
behavior_boundaries:
  will_always:    # 角色一定会做的事
    - "面对弱者时出手相助"
    - "在公开场合维持冷静形象"
  will_never:     # 角色绝对不会做的事
    - "背叛师门"
    - "对无辜之人使用禁术"
  under_pressure: # 极端情况下可能的异常行为（需铺垫后才可触发）
    - "被逼入绝境时可能暴走，但必须有2章以上压力铺垫"
  speech_pattern: # 说话方式
    - "倾向使用短句"
    - "不用感叹号"
    - "会引用古诗词"
```

**为什么这样做**：长篇创作中 AI 最容易犯的错就是让角色做出违反人设的事。有了行为边界，后续"即时审查"步骤可以自动检测 OOC。

---

### 3.3 大纲生成（增强）

**保持现有 4 阶段流水线**，在 Phase 2（逐章大纲）之后增加一个子步骤：

#### 增强点：每章事件清单生成

在大纲精修之后，为每章生成一份结构化"事件清单"：

```yaml
chapter_events:
  chapter_number: 15
  core_conflict: "主角发现师兄暗中与魔族勾结"
  turning_point: "师兄主动承认，但揭露了更大的阴谋"
  new_foreshadowing:           # 本章新埋的伏笔
    - id: "F015-1"
      content: "师兄提到'九天之上的那位'"
      expected_payoff_chapter: 25
  resolve_foreshadowing:       # 本章回收的伏笔
    - id: "F008-2"
      content: "第8章神秘信件的发送者揭晓"
  character_appearances:       # 出场角色及其在本章的作用
    - name: "主角"
      role: "推动者"
      emotional_arc: "震惊→愤怒→冷静分析"
    - name: "师兄"
      role: "揭示者"
      emotional_arc: "平静→决绝"
  relationship_changes:        # 关系变化
    - pair: ["主角", "师兄"]
      from: "信任的师兄弟"
      to: "复杂的对立+暗中同盟"
```

**为什么这样做**：
1. 事件清单是后续"章节写作循环"中"剧情推演"步骤的核心输入
2. 伏笔预规划避免了"写到后面忘了前面埋了什么"
3. 明确关系变化使角色发展有据可循

#### 实现要点

| 项目 | 方案 |
|------|------|
| PromptSubStep | `CHAPTER_EVENT_PLAN` |
| 数据存储 | 扩展 `chapter_outlines` 表增加 `event_plan` JSON 字段 |
| 模型参数 | maxTokens=1024, temperature=0.6 |
| 上下文 | 前 3 章事件清单 + 本章大纲 + 出场角色 |
| 哨兵 | `[[SECTION:EVENT:{chNum}:{volNum}]]` |

---

### 3.4 章节写作（重大改进：7 步精细循环）

这是整个改进方案的核心。当前系统对每章只做一次 AI 调用直接生成正文，改进后每章经历 7 个子步骤的精细循环。

#### 总览流程

```
┌─────────────────────────────────────────────────────────────────┐
│                    每章创作循环（7 步）                            │
│                                                                 │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 1: 前文梳理                                     │      │
│  │  - 人物当前状态快照                                    │      │
│  │  - 活跃伏笔清单（待回收 / 本章应回收）                  │      │
│  │  - 前文关键事件摘要（最近 3 章）                        │      │
│  │  - 场景连续性检查（时间/地点/氛围衔接）                  │      │
│  └───────────────────────────────┬───────────────────────┘      │
│                                  ↓                              │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 2: 剧情推演                                     │      │
│  │  - 本章核心冲突展开方式                                │      │
│  │  - 转折点设计（情感/事件转折）                          │      │
│  │  - 对话策略（关键对话的核心要点）                       │      │
│  │  - 节奏规划（场景划分与节奏标注）                       │      │
│  └───────────────────────────────┬───────────────────────┘      │
│                                  ↓                              │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 3: 正文生成                                     │      │
│  │  - 带入完整上下文：规则文档 + 风格指纹 + 世界观        │      │
│  │  - 带入精细上下文：前文梳理结果 + 剧情推演结果          │      │
│  │  - 带入角色约束：出场角色行为边界                       │      │
│  │  - 带入大纲：本章大纲 + 事件清单                       │      │
│  └───────────────────────────────┬───────────────────────┘      │
│                                  ↓                              │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 4: 即时审查                                     │      │
│  │  - 角色行为一致性检查（对照行为边界）                   │      │
│  │  - 情节逻辑检查（对照剧情推演计划）                    │      │
│  │  - 风格一致性检查（对照风格指纹）                      │      │
│  │  - 伏笔执行检查（计划回收的是否已回收）                 │      │
│  │  → 输出: 问题列表 + 严重程度（Critical / Warning）     │      │
│  └───────────────────────────────┬───────────────────────┘      │
│                                  ↓                              │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 5: 内容优化                                     │      │
│  │  - 若有 Critical 问题: 基于审查结果修正正文             │      │
│  │  - 若仅 Warning 或无问题: 跳过此步                     │      │
│  │  → 输出: 修正后的正文（或原文不变）                    │      │
│  └───────────────────────────────┬───────────────────────┘      │
│                                  ↓                              │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 6: 故事线更新                                   │      │
│  │  - 更新角色状态快照（情感状态、位置、关系变化）          │      │
│  │  - 更新伏笔追踪表（标记已回收、新增本章伏笔）           │      │
│  │  - 生成本章摘要（供后续章节参考）                       │      │
│  │  - 更新关系图谱（角色间关系变化）                       │      │
│  └───────────────────────────────┬───────────────────────┘      │
│                                  ↓                              │
│  ┌───────────────────────────────────────────────────────┐      │
│  │  Step 7: 周期性深度审查（每 N 章触发）                  │      │
│  │  - 触发条件: chapterNum % reviewInterval == 0          │      │
│  │  - 审查范围: 最近 N 章的整体一致性                      │      │
│  │  - 检查: 节奏是否符合 tension_curve 规则                │      │
│  │  - 检查: 是否有遗忘超过 10 章的活跃伏笔                │      │
│  │  - 检查: 角色成长弧线是否合理推进                       │      │
│  │  - 输出: 审查报告（可能影响后续章节大纲微调）            │      │
│  └───────────────────────────────────────────────────────┘      │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### Step 1: 前文梳理

**目的**：在动笔前，让 AI"回忆"当前的故事状态，避免章间矛盾。

**输入**：
- 前 3 章的摘要（来自 Step 6 的输出）
- 上一章末尾 500 字（确保场景衔接）
- 当前角色状态快照（来自前一章的 Step 6 更新）
- 活跃伏笔列表（未回收的伏笔，按预期回收章节排序）
- 本章事件清单（来自 3.3 的增强）

**输出**：
```yaml
context_briefing:
  time_continuity: "承接第14章结尾的夜晚，当前时间约为子时"
  location: "仍在无名山谷密室中"
  mood_atmosphere: "紧张压抑，暗流涌动"
  active_characters:
    - name: "主角"
      current_state: "刚发现师兄的秘密，处于震惊中"
      emotional_tone: "强压怒火，保持理性"
    - name: "师兄"
      current_state: "已暴露秘密，但态度镇定"
  pending_foreshadowing:
    - id: "F008-2"
      content: "神秘信件"
      urgency: "本章必须回收"
    - id: "F012-1"
      content: "九天之上"
      urgency: "5章内回收"
  writing_notes: "注意保持前章结尾的紧张感，不可突然转为轻松"
```

**实现参数**：
- PromptSubStep: `CHAPTER_CONTEXT_BRIEFING`
- maxTokens: 1024
- temperature: 0.3（低温度确保准确性）

---

#### Step 2: 剧情推演

**目的**：在生成正文前，先对本章的核心情节进行结构化推演，避免"平铺直叙"。

**输入**：
- 前文梳理结果（Step 1 输出）
- 本章大纲 + 事件清单
- 写作规则中的节奏要求
- 出场角色的行为边界

**输出**：
```yaml
plot_reasoning:
  opening_hook: "以师兄一句意味深长的话开篇，制造悬念"
  conflict_escalation:
    - beat: "师兄揭露更大阴谋，主角面临站队抉择"
    - beat: "外部势力突然介入（第三方出现）"
    - beat: "被迫暂时合作，关系从对立变为复杂同盟"
  turning_point: "主角发现自己也被牵涉其中，立场动摇"
  emotional_arc: "震惊 → 愤怒 → 动摇 → 隐忍接受"
  scene_breakdown:
    - scene: 1
      location: "密室"
      characters: ["主角", "师兄"]
      purpose: "信息揭露 + 冲突对峙"
      word_target: 1200
    - scene: 2
      location: "密室外走廊"
      characters: ["主角"]
      purpose: "内心独白 + 决策"
      word_target: 800
    - scene: 3
      location: "山谷入口"
      characters: ["主角", "师兄", "神秘人"]
      purpose: "第三方介入 + 被迫合作"
      word_target: 1000
  chapter_ending: "以三人结伴离开山谷作结，暗示前路未知"
  key_dialogues:
    - between: ["主角", "师兄"]
      purpose: "揭露信息同时展现两人性格差异"
      tone: "表面平静实则暗藏锋芒"
```

**实现参数**：
- PromptSubStep: `CHAPTER_PLOT_REASONING`
- maxTokens: 1536
- temperature: 0.7

---

#### Step 3: 正文生成

**目的**：基于充分的上下文和推演结果生成高质量正文。

**与当前实现的差异**：当前系统只带入"前一章内容 + 本章大纲 + 出场角色卡"，改进后带入：

| 上下文类别 | 当前 | 改进后 |
|-----------|------|--------|
| 写作规则 | ❌ | ✅ 核心规则摘要 |
| 风格指纹 | ❌ | ✅ 风格约束 |
| 前文梳理 | ❌ | ✅ Step 1 完整输出 |
| 剧情推演 | ❌ | ✅ Step 2 完整输出 |
| 本章大纲 | ✅ | ✅ |
| 事件清单 | ❌ | ✅ 核心冲突+伏笔计划 |
| 角色行为边界 | ❌ | ✅ 出场角色边界 |
| 前一章内容 | ✅ (全文) | ✅ (末尾 1000 字) |
| 角色状态 | 有但简单 | ✅ 结构化当前状态 |

**实现参数**：
- PromptSubStep: `CHAPTER_CONTENT_GENERATION`（复用现有 CHAPTER_WRITING handler，增强 prompt）
- maxTokens: max(8192, chapterWordCountMax * 2 + 2000)（保持不变）
- temperature: 0.85（保持不变）

**注意**：上下文注入需要做截断管理，总 token 数控制在模型上下文窗口的 70% 以内。优先级：规则 > 推演 > 梳理 > 大纲 > 角色 > 前文。

---

#### Step 4: 即时审查

**目的**：每章写完立即进行快速质检，不等到最后批量校对。

**审查维度**：

| 维度 | 检查内容 | 严重级别判定 |
|------|----------|-------------|
| OOC 检查 | 角色行为是否违反行为边界 | Critical: will_never 被触发 |
| 逻辑检查 | 情节是否与推演计划严重偏离 | Warning: 轻微偏离; Critical: 核心冲突缺失 |
| 风格检查 | 文风是否与风格指纹偏差过大 | Warning: 偏差明显 |
| 伏笔检查 | 计划回收的伏笔是否确实回收了 | Warning: 未回收但可延后; Critical: 语境已不适合再回收 |
| 连续性 | 时间/地点/状态是否与上文衔接 | Critical: 明显矛盾 |

**输出格式**：
```yaml
review_result:
  overall_score: 8.5  # 1-10
  issues:
    - severity: "CRITICAL"
      dimension: "OOC"
      description: "主角在第3段对弱者见死不救，违反行为边界'面对弱者时出手相助'"
      location: "第3段第2句"
      suggestion: "改为主角想救但被形势所迫无法出手，内心痛苦"
    - severity: "WARNING"
      dimension: "STYLE"
      description: "第5段连续使用4个短句，不符合'长短交替'的节奏要求"
      location: "第5段"
      suggestion: "合并部分短句为复合句"
  pass: false  # 是否通过（无 CRITICAL 即通过）
```

**实现参数**：
- PromptSubStep: `CHAPTER_INSTANT_REVIEW`
- maxTokens: 1536
- temperature: 0.2（低温度确保审查严谨）

---

#### Step 5: 内容优化

**目的**：对审查发现的 Critical 问题进行针对性修正。

**触发条件**：
- 审查结果中有 **CRITICAL** 级别问题 → 执行优化
- 仅有 WARNING 或无问题 → **跳过此步**，直接进入 Step 6

**输入**：
- 当前正文
- 审查报告（仅 Critical 问题）
- 剧情推演结果（作为修正参考）
- 角色行为边界（作为 OOC 修正参考）

**实现要点**：
- 一次修正最多处理 3 个 Critical 问题（避免大幅改写导致新问题）
- 修正后不再次审查（避免无限循环），直接进入 Step 6
- 保留修正前的版本（类似当前校对的 `contentBeforeFix` 机制）

**实现参数**：
- PromptSubStep: `CHAPTER_CONTENT_OPTIMIZE`
- maxTokens: max(8192, chapterWordCountMax * 2 + 2000)
- temperature: 0.5

---

#### Step 6: 故事线更新

**目的**：每章写完后更新全局追踪数据，确保后续章节可以准确引用当前状态。

**更新内容**：

| 追踪项 | 更新操作 | 存储位置 |
|--------|----------|----------|
| 角色状态 | 更新当前情感/位置/关系 | `chapter.characterStates`（现有） |
| 伏笔表 | 标记已回收 + 新增本章伏笔 | `foreshadowing_tracker`（新表） |
| 章节摘要 | 生成 200 字以内摘要 | `chapter.plotSummary`（现有） |
| 关系变化 | 更新角色间关系描述 | `relationship_changes`（新表） |

**与当前系统的对比**：
- 当前的 `generateCharacterStates()` 只做角色状态快照，改进后将其扩展为完整的"故事线更新"，同时维护伏笔和关系
- 当前的 `plotSummary` 在校对阶段才生成，改进后在写作阶段即时生成

**实现参数**：
- PromptSubStep: `CHAPTER_STORYLINE_UPDATE`
- maxTokens: 1024
- temperature: 0.3

---

#### Step 7: 周期性深度审查

**目的**：每 N 章（默认 5 章）进行一次跨章回顾，检查宏观一致性。

**触发条件**: `chapterNumber % reviewInterval == 0`（reviewInterval 可配置，默认 5）

**审查内容**：

1. **节奏审查**：最近 N 章的 tension_curve 是否符合规则要求
2. **伏笔超期检查**：是否有活跃伏笔超过预期回收章节 5 章以上仍未回收
3. **角色弧线审查**：主要角色在这 N 章中是否有合理的成长/变化
4. **主线推进审查**：核心矛盾是否在推进，有无原地踏步
5. **风格一致性抽检**：随机抽取片段与风格指纹对比

**输出**：
```yaml
periodic_review:
  chapters_reviewed: [11, 12, 13, 14, 15]
  pacing_assessment: "节奏偏平，第12-14章缺少明显冲突升级"
  overdue_foreshadowing:
    - id: "F003-1"
      planted_chapter: 3
      expected_payoff: 10
      current_chapter: 15
      recommendation: "在第16-17章安排回收"
  character_arc_notes:
    - character: "主角"
      observation: "这5章情感变化合理，从信任到怀疑的转变有足够铺垫"
    - character: "配角A"
      observation: "最近3章几乎无出场，需在下一段情节中重新引入"
  storyline_momentum: "主线有推进但偏慢，建议加快第三方势力介入节奏"
  next_chapters_suggestion: "第16-20章建议安排一个大型冲突事件以提升节奏"
```

**后续影响**：
- 如果发现超期伏笔，将其标记为"urgent"，在后续章节的 Step 1 前文梳理中高亮提示
- 如果发现节奏问题，生成"后续建议"供后续章节的 Step 2 推演参考
- **不回溯修改已完成章节**（避免复杂度爆炸），仅影响前向生成

**实现参数**：
- PromptSubStep: `PERIODIC_DEEP_REVIEW`
- maxTokens: 2048
- temperature: 0.4
- 超时倍率: ×3

---

### 3.5 润色修改（增强）

**保持现有逐章润色流程**，增加以下改进：

#### 增强点：风格一致性校验

在润色 prompt 中增加：
1. 注入风格指纹作为参考
2. 要求润色时保持原始风格特征不变
3. 如有 polishNote（用户修改意见），仍优先执行用户意见

#### 增强点：与即时审查结果联动

如果章节在写作阶段的即时审查中有未处理的 WARNING 级别问题，将其作为润色参考注入 prompt，在润色环节一并处理。

---

### 3.6 校对精修（保持 + 轻微增强）

**保持现有 5 维检查 + 修复流程不变**。

#### 增强点：全书级审查（完成所有章节校对后）

在所有章节逐章校对修复完成后，增加一个可选的"全书级审查"步骤：

- 汇总所有章节的 plotSummary，检查全书核心矛盾是否得到解决
- 检查是否有伏笔仍处于未回收状态
- 检查主角成长弧线的完整性
- 生成一份"全书审查报告"供用户参考

**这一步是可选的**（通过 AutoRun step config 控制），因为如果写作阶段的即时审查和周期性审查做得好，到这一步问题应该已经很少了。

---

## 4. 新增核心机制

### 4.1 伏笔追踪系统

**当前痛点**：现有校对阶段的 FORESHADOWING 检查只是在最后被动发现遗忘的伏笔，改进后在大纲阶段就预先规划，在写作阶段主动追踪。

#### 数据模型

```sql
CREATE TABLE foreshadowing_tracker (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    foreshadowing_id VARCHAR(20) NOT NULL,  -- 如 F015-1
    content TEXT NOT NULL,                   -- 伏笔内容描述
    planted_chapter INT NOT NULL,            -- 埋设章节
    expected_payoff_chapter INT,             -- 预期回收章节
    actual_payoff_chapter INT,              -- 实际回收章节（null=未回收）
    status VARCHAR(20) DEFAULT 'ACTIVE',    -- ACTIVE / RESOLVED / ABANDONED
    urgency VARCHAR(20) DEFAULT 'NORMAL',   -- NORMAL / URGENT / OVERDUE
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

#### 生命周期

```
大纲阶段（事件清单）: 预规划伏笔 → status=ACTIVE
写作阶段 Step 1: 提取活跃伏笔列表 → 标注本章应回收的
写作阶段 Step 3: 正文中执行伏笔回收
写作阶段 Step 4: 审查伏笔执行情况
写作阶段 Step 6: 更新追踪表（标记已回收 / 新增 / 标记超期）
周期性审查 Step 7: 检查超期伏笔，调整 urgency
```

---

### 4.2 写作规则约束机制

**实现方式**：

1. **注入点**：写作规则文档的核心摘要（约 500 字）注入到每个 AI 调用的 system prompt 中
2. **分级注入**：
   - 世界观/角色生成：注入叙事视角 + 禁忌列表
   - 大纲生成：注入节奏规则 + 章节结构要求
   - 正文生成：注入全部规则
   - 即时审查：注入全部规则（作为审查标准）
3. **可编辑**：用户可在创作准备步骤完成后手动编辑规则文档

---

### 4.3 周期性审查配置

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `review_interval` | 5 | 每 N 章触发一次深度审查 |
| `foreshadowing_overdue_threshold` | 10 | 超过预期回收章节 N 章标记为 OVERDUE |
| `max_active_foreshadowing` | 15 | 活跃伏笔超过此数发出警告 |
| `enable_periodic_review` | true | 是否启用周期性审查 |

---

### 4.4 风格一致性锁定

**实现方式**：

1. 在创作准备阶段生成"风格指纹"
2. 正文生成时，将风格指纹作为约束注入 prompt
3. 即时审查时，对比生成文本与风格指纹的偏差
4. 润色阶段，以风格指纹为参照进行风格校正

**风格指纹内容**：
```yaml
style_fingerprint:
  avg_sentence_length: 25        # 平均句长（字）
  dialogue_ratio: 0.35           # 对话占比
  description_density: "中等"     # 描写密度
  rhetorical_devices: ["比喻", "排比"]  # 常用修辞
  vocabulary_samples:             # 高频特征词
    - "果然"
    - "不由得"
    - "淡淡地"
  paragraph_rhythm: "2-3句描写 → 1句对话 → 1-2句反应" # 段落节奏模式
  tone: "沉稳内敛，偶有诙谐"      # 整体调性
```

---

### 4.5 角色行为边界（防 OOC）

**机制**：

1. 角色设计阶段：在精修时生成行为边界字段
2. 正文生成时：将出场角色的行为边界注入 prompt 作为硬约束
3. 即时审查时：以行为边界为标准检测 OOC
4. 特殊处理：`under_pressure` 类行为需要检查是否有足够铺垫

**OOC 检测逻辑**：
```
IF 角色行为 in will_never:
  → CRITICAL，必须修正
IF 角色行为 not in will_always AND 情境要求:
  → WARNING，审查是否有合理理由
IF 角色行为 in under_pressure AND 无前文铺垫:
  → CRITICAL，要么增加铺垫要么修正行为
```

---

## 5. 与现有架构的对接方案

### 5.1 策略模式扩展

改进后的工作流作为**新策略**实现，而非替换默认策略：

```java
// 现有策略保持不变
@Component
public class DefaultAutoRunStrategy implements AutoRunStrategy {
    public String getName() { return "DEFAULT"; }
    // ... 现有逻辑
}

// 新增改进版策略
@Component
public class EnhancedAutoRunStrategy implements AutoRunStrategy {
    public String getName() { return "ENHANCED"; }

    public void execute(AutoRunContext ctx) {
        // Step 0: 创作准备
        runPreparation(ctx);
        // Step 1: 世界观（增强）
        runWorldBuilding(ctx);
        // Step 2: 角色（增强）
        runCharacterDesign(ctx);
        // Step 3: 大纲（增强）
        runOutlineGeneration(ctx);
        // Step 4: 章节写作（7步循环）
        runChapterWritingEnhanced(ctx);
        // Step 5: 润色（增强）
        runPolishing(ctx);
        // Step 6: 校对
        runProofreading(ctx);
    }

    private void runChapterWritingEnhanced(AutoRunContext ctx) {
        for (int ch = startChapter; ch <= totalChapters; ch++) {
            // 7步循环
            String briefing = contextBriefing(ctx, ch);      // Step 1
            String reasoning = plotReasoning(ctx, ch);         // Step 2
            String content = generateContent(ctx, ch);         // Step 3
            ReviewResult review = instantReview(ctx, ch);      // Step 4
            if (review.hasCritical()) {
                content = optimizeContent(ctx, ch, review);    // Step 5
            }
            updateStoryline(ctx, ch);                          // Step 6
            if (ch % reviewInterval == 0) {
                periodicReview(ctx, ch);                        // Step 7
            }
        }
    }
}
```

**优势**：
- 用户可以通过 `project.autoRunStrategy` 字段选择使用哪个策略
- 默认策略不受影响，不破坏现有功能
- 可以渐进式开发和测试

---

### 5.2 新增 PromptSubStep

在现有 15 个 PromptSubStep 基础上新增：

| PromptSubStep | 父步骤 | 用途 |
|---------------|--------|------|
| `WRITING_RULES_GENERATION` | PREPARATION | 生成写作规则文档 |
| `STYLE_ANALYSIS` | PREPARATION | 风格指纹分析 |
| `CHAPTER_EVENT_PLAN` | OUTLINE_GENERATION | 每章事件清单 |
| `CHAPTER_CONTEXT_BRIEFING` | CHAPTER_WRITING | 前文梳理 |
| `CHAPTER_PLOT_REASONING` | CHAPTER_WRITING | 剧情推演 |
| `CHAPTER_CONTENT_GENERATION` | CHAPTER_WRITING | 正文生成（增强版） |
| `CHAPTER_INSTANT_REVIEW` | CHAPTER_WRITING | 即时审查 |
| `CHAPTER_CONTENT_OPTIMIZE` | CHAPTER_WRITING | 内容优化 |
| `CHAPTER_STORYLINE_UPDATE` | CHAPTER_WRITING | 故事线更新 |
| `PERIODIC_DEEP_REVIEW` | CHAPTER_WRITING | 周期性深度审查 |

---

### 5.3 数据模型变更

#### 新增表

```sql
-- V44: 写作规则
CREATE TABLE writing_rules (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    rule_type VARCHAR(50) NOT NULL,    -- NARRATIVE, STYLE, TABOO, PACING, STRUCTURE
    content TEXT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- V45: 伏笔追踪
CREATE TABLE foreshadowing_tracker (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    foreshadowing_id VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    planted_chapter INT NOT NULL,
    expected_payoff_chapter INT,
    actual_payoff_chapter INT,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    urgency VARCHAR(20) DEFAULT 'NORMAL',
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- V46: 关系变化追踪
CREATE TABLE relationship_changes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    chapter_number INT NOT NULL,
    character_a VARCHAR(100) NOT NULL,
    character_b VARCHAR(100) NOT NULL,
    relationship_before TEXT,
    relationship_after TEXT,
    trigger_event TEXT,
    created_at TIMESTAMP
);

-- V47: 周期性审查报告
CREATE TABLE periodic_reviews (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    chapter_range_start INT NOT NULL,
    chapter_range_end INT NOT NULL,
    review_content TEXT NOT NULL,
    suggestions TEXT,
    created_at TIMESTAMP
);

-- V48: 风格指纹
CREATE TABLE style_fingerprints (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    fingerprint_content TEXT NOT NULL,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);
```

#### 现有表扩展

```sql
-- chapter_outlines 增加事件清单字段
ALTER TABLE chapter_outlines ADD COLUMN event_plan TEXT;

-- characters 增加行为边界字段
ALTER TABLE characters ADD COLUMN behavior_boundaries TEXT;

-- chapters 增加循环子步骤进度字段
ALTER TABLE chapters ADD COLUMN writing_substep VARCHAR(50);
-- 值: BRIEFING_DONE, REASONING_DONE, CONTENT_DONE, REVIEWED, OPTIMIZED, TRACKED

-- chapters 增加即时审查结果字段
ALTER TABLE chapters ADD COLUMN instant_review_result TEXT;
```

---

### 5.4 上下文 Token 管理

7 步循环带来了更多上下文注入需求，需要做好 token 预算管理：

| 步骤 | 预估注入 token | 优化策略 |
|------|---------------|----------|
| Step 3 正文生成 | ~6000 tokens | 分级截断：规则(500) + 推演(1000) + 梳理(800) + 大纲(500) + 角色(1500) + 前文(1500) |
| Step 4 即时审查 | ~5000 tokens | 正文全文 + 规则(500) + 行为边界(500) |
| 其他步骤 | ~2000-3000 | 按需裁剪 |

**策略**：
1. 利用现有 `ContextSummaryService` 对长内容做摘要压缩
2. 对写作规则做"核心规则摘要"版本（~500 token），完整版仅在审查步骤使用
3. 前文内容使用摘要而非全文（除了上一章末尾需要原文确保衔接）

---

### 5.5 断点续跑扩展

7 步循环中每个子步骤完成后都需要记录进度：

```
章节写作断点恢复逻辑:
1. 找到第一个 writing_substep != "TRACKED" 的章节
2. 根据 writing_substep 值决定从哪个子步骤恢复:
   - null/空: 从 Step 1（前文梳理）开始
   - BRIEFING_DONE: 从 Step 2（剧情推演）恢复
   - REASONING_DONE: 从 Step 3（正文生成）恢复
   - CONTENT_DONE: 从 Step 4（即时审查）恢复
   - REVIEWED: 从 Step 5（内容优化）恢复
   - OPTIMIZED: 从 Step 6（故事线更新）恢复
   - TRACKED: 章节完成，进入下一章
```

---

### 5.6 超时配置

| 子步骤 | 超时倍率 | 说明 |
|--------|----------|------|
| 前文梳理 | ×1 | 输出短，AI 理解为主 |
| 剧情推演 | ×2 | 中等输出 |
| 正文生成 | ×10 | 长文本生成 |
| 即时审查 | ×2 | 分析为主 |
| 内容优化 | ×10 | 长文本重写 |
| 故事线更新 | ×1 | 结构化短输出 |
| 周期性审查 | ×3 | 多章分析 |

---

## 6. 实施优先级建议

### Phase 1 — 基础设施（预计工作量：中）

**目标**：搭建新策略框架 + 核心数据模型

1. 新增 `EnhancedAutoRunStrategy` 空壳
2. 创建数据库迁移（写作规则、伏笔追踪、关系变化等表）
3. 新增 PromptSubStep 枚举值
4. 新增断点续跑逻辑（`writing_substep` 字段）
5. 前端：策略选择器（项目设置中选择 DEFAULT / ENHANCED）

### Phase 2 — 前置步骤（预计工作量：小）

**目标**：实现创作准备步骤

6. 实现写作规则生成（`WRITING_RULES_GENERATION`）
7. 实现风格指纹分析（`STYLE_ANALYSIS`）
8. 规则/指纹编辑 UI

### Phase 3 — 大纲增强（预计工作量：小）

**目标**：事件清单生成

9. 实现每章事件清单生成（`CHAPTER_EVENT_PLAN`）
10. 事件清单 UI 展示

### Phase 4 — 写作循环核心（预计工作量：大）

**目标**：实现 7 步循环的核心步骤

11. Step 1: 前文梳理（`CHAPTER_CONTEXT_BRIEFING`）
12. Step 2: 剧情推演（`CHAPTER_PLOT_REASONING`）
13. Step 3: 正文生成（增强版，带入完整上下文）
14. Step 4: 即时审查（`CHAPTER_INSTANT_REVIEW`）
15. Step 5: 内容优化（`CHAPTER_CONTENT_OPTIMIZE`）
16. Step 6: 故事线更新（`CHAPTER_STORYLINE_UPDATE`）
17. 伏笔追踪系统完整集成

### Phase 5 — 周期性审查（预计工作量：小）

**目标**：实现周期性深度审查

18. Step 7: 周期性深度审查（`PERIODIC_DEEP_REVIEW`）
19. 审查报告存储与展示

### Phase 6 — 角色增强（预计工作量：小）

**目标**：行为边界系统

20. 角色精修时生成行为边界
21. 行为边界注入正文生成 + 即时审查

### Phase 7 — 润色增强 & 联调（预计工作量：中）

**目标**：完善润色步骤 + 端到端测试

22. 润色步骤注入风格指纹
23. 润色步骤联动即时审查 WARNING
24. 全书级审查（可选步骤）
25. 端到端测试：完整跑一个 10 章小说

---

## 7. 成本与收益分析

### AI 调用成本对比

| 模式 | 每章 AI 调用次数 | 100 章总调用 | 说明 |
|------|-----------------|-------------|------|
| 当前 DEFAULT | 1（正文）+ 1（角色状态）+ 1（标题）= 3 | 300 | 加上校对 5+1=6，共 ~900 |
| 改进 ENHANCED | 6-7（循环）+ 0.2（周期审查分摊）≈ 7 | 700 | 加上校对（预期问题减少）~400，共 ~1100 |

**成本增加约 20%**，但：
- 校对阶段发现的问题预计减少 60-80%（大量问题在写作阶段已修正）
- 修复成本大幅降低（即时修正 vs 回溯修复）
- 最终作品质量显著提升（一致性、连贯性、角色塑造）

### 执行时间对比

| 模式 | 每章预估时间 | 100 章预估总时间 |
|------|-------------|-----------------|
| DEFAULT | ~2 分钟 | ~3.5 小时 |
| ENHANCED | ~8-10 分钟 | ~15-17 小时 |

时间增加是预期内的——本方案牺牲速度换质量。用户可以通过策略选择器自由选择：
- 快速出稿用 DEFAULT
- 精品创作用 ENHANCED

---

## 8. 开放问题与后续思考

1. **循环次数上限**：如果即时审查持续发现 Critical 问题怎么办？当前设计是只修正一次不再循环，但是否需要设置最大重试次数（如 2 次）？
2. **伏笔追踪的准确性**：AI 提取的伏笔可能不准确（误报/漏报），需要考虑用户可手动编辑伏笔表的 UI。
3. **长篇 token 压力**：100+ 章时伏笔表可能很长，需要定期清理已解决的伏笔只保留活跃的。
4. **策略混合模式**：是否允许对部分步骤使用 ENHANCED，部分使用 DEFAULT？例如前 30 章用 ENHANCED 建立基调，后续用 DEFAULT 加速。
5. **用户干预点**：7 步循环是否应该允许用户在某些子步骤后暂停审阅？如剧情推演后让用户确认再生成正文。

---

## 附录 A: 与参考文章"5 阶段 + 9 技能 + 7 循环"的对应关系

| 参考文章概念 | 本方案对应 | 实现位置 |
|-------------|-----------|----------|
| 阶段1:初始化 | Step 0 创作准备 | 写作规则 + 风格锁定 |
| 阶段2:计划 | Step 3 大纲生成（增强） | 事件清单 + 伏笔预规划 |
| 阶段3:章节循环 | Step 4 七步循环 | 完整对应 |
| 阶段4:审查 | Step 4.4 即时审查 + Step 4.7 周期审查 | 双层审查 |
| 阶段5:输出 | Step 5 润色 + Step 6 校对 | 保持现有 |
| 技能:上下文追踪 | Step 4.1 前文梳理 + Step 4.6 故事线更新 | 伏笔表 + 角色状态 |
| 技能:场景生成 | Step 4.3 正文生成 | 带入完整上下文 |
| 技能:一致性检查 | Step 4.4 即时审查 | OOC + 逻辑 + 连续性 |
| 技能:情节推理 | Step 4.2 剧情推演 | 冲突+转折+节奏规划 |
| 技能:修正 | Step 4.5 内容优化 | 基于审查的针对性修正 |
| 7循环机制 | Step 4 每章7步 | 完整对应（保持精神，适配架构） |
