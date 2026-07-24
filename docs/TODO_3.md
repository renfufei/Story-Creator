# TODO

## 待办事项

---

### 3. 番外篇功能（Side Story / Gaiden）

#### 问题

当前系统仅支持线性的主线创作流程（世界观→角色→大纲→写作→润色），缺少对"番外"内容的支持。番外是网络小说中常见的创作形式，用于：

1. **丰富世界观**：探索主线未涉及的角色关系、历史事件或平行可能性
2. **满足读者需求**：针对人气角色或遗留线索进行补充叙事
3. **增加作品厚度**：在不打断主线节奏的前提下扩展故事世界

#### 核心理念

> **番外是主线的延伸而非替代。** 每个番外复用项目已有的角色、地理位置和世界观设定，但拥有独立的故事线和章节结构，相当于一部小型的附加卷。

#### 设计方案

##### 3.1 番外定义

每个项目可关联多个番外，每个番外具备：

| 属性 | 说明 |
|---|---|
| `title` | 番外标题 |
| `description` | 番外简介/创作意图 |
| `type` | 番外类型：`SUPPLEMENTARY`（附加，主线之后的延伸）/ `TANGENTIAL`（题外，与主线平行的独立故事） |
| `status` | 创作状态：`NOT_STARTED` / `IN_PROGRESS` / `COMPLETED` |
| `attached_volume` | 关联的卷号（可选，表示此番外附属于哪一卷之后） |
| `sort_order` | 排序顺序 |

##### 3.2 番外与主线的关系

番外可以从主项目中**选择性引用**以下资源：

- **角色**：从项目角色库中选择出场角色（可选部分或全部）
- **卷弧线**：引用某卷的故事弧作为背景上下文
- **大纲设定**：引用世界观设定、力量体系等
- **地理位置/场景**：复用世界观中已建立的地点

番外**独立拥有**：

- 自己的章节大纲和章节内容
- 独立的创作指导/提示词模板
- 独立的写作风格设定（可选，默认继承主线）

##### 3.3 创作流程

番外的创作流程是主线的简化版：

```
创建番外
  ├── 选择关联角色（从项目角色库勾选）
  ├── 选择引用的卷弧线/世界观片段
  ├── 填写/生成番外大纲（独立提示词模板）
  ├── 生成章节大纲（1-N 章，通常较短）
  ├── 逐章写作（带入选定角色 + 世界观上下文）
  └── 润色/校对
```

##### 3.4 提示词模板

番外需要独立的提示词模板集（`PromptSubStep` 新增）：

- `SIDE_STORY_OUTLINE`：番外大纲生成（输入：选定角色、世界观、创作意图）
- `SIDE_STORY_CHAPTER_OUTLINE`：番外章节大纲
- `SIDE_STORY_WRITING`：番外章节写作

模板中的关键变量：
- `{{selectedCharacters}}`：用户选定的角色卡片
- `{{worldSetting}}`：世界观设定（或对应 facet）
- `{{volumeArc}}`：关联卷的弧线（如有）
- `{{sideStoryOutline}}`：番外总大纲
- `{{creativeGuidance}}`：用户提供的创作指导/方向

##### 3.5 数据模型

新增 `side_stories` 表：

```sql
CREATE TABLE side_stories (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    type VARCHAR(20) NOT NULL DEFAULT 'SUPPLEMENTARY',  -- SUPPLEMENTARY / TANGENTIAL
    status VARCHAR(20) NOT NULL DEFAULT 'NOT_STARTED',
    attached_volume INT,                                 -- 关联卷号（可选）
    sort_order INT DEFAULT 0,
    outline TEXT,                                        -- 番外大纲
    creative_guidance TEXT,                              -- 创作指导
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (project_id) REFERENCES projects(id)
);
```

新增 `side_story_characters` 关联表：

```sql
CREATE TABLE side_story_characters (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    side_story_id BIGINT NOT NULL,
    character_id BIGINT NOT NULL,
    UNIQUE(side_story_id, character_id),
    FOREIGN KEY (side_story_id) REFERENCES side_stories(id),
    FOREIGN KEY (character_id) REFERENCES characters(id)
);
```

新增 `side_story_chapters` 表：

```sql
CREATE TABLE side_story_chapters (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    side_story_id BIGINT NOT NULL,
    chapter_number INT NOT NULL,
    title VARCHAR(200),
    outline_summary TEXT,
    content TEXT,
    status VARCHAR(20) DEFAULT 'NOT_STARTED',
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    FOREIGN KEY (side_story_id) REFERENCES side_stories(id)
);
```

##### 3.6 全文阅读集成

在 Reader 页面中，番外作为**附属卷**出现在其关联卷之后：

```
第一卷：起源
  第1章 ...
  第2章 ...
  ...
  【番外】初遇时的另一种可能    ← attached_volume = 1 的番外
第二卷：觉醒
  第1章 ...
  ...
  【番外】被遗忘的日常          ← attached_volume = 2 的番外
番外集                          ← attached_volume = NULL 的番外归入末尾
  【番外】如果当初选了另一条路
```

##### 3.7 前端呈现

- 项目信息页新增**番外管理**区域：
  - 番外列表（卡片式展示，显示标题、类型、状态、关联卷）
  - 新建番外按钮 → 进入番外创建向导
  - 每个番外卡片可展开进入番外编辑/创作流程
- 番外创建向导：
  - Step 1：填写标题、简介、类型、关联卷
  - Step 2：勾选关联角色（展示项目所有角色，支持多选）
  - Step 3：填写创作指导（可选）
  - Step 4：生成/编辑番外大纲
- 番外创作页面：简化版 workflow，仅含大纲→写作→润色步骤

##### 3.8 兼容性考虑

- 现有项目不受影响，番外功能为纯增量
- 番外的写作流程可复用现有的 `WorkflowEngine` 基础设施（章节生成、SSE 流式等）
- AutoRun 暂不自动创作番外（需用户手动触发）
- 导出功能需适配：番外按附属关系嵌入导出文件对应位置

---
