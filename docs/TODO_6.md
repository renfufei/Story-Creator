# TODO 6: AI聊天对话功能

## 背景

目前项目已经集成了大模型接口（文字、语音、图片），但用户有创意需要和AI大模型探讨时还很不方便。需要增加一个通用的AI聊天对话功能。

## 需求

### 入口

- 首页增加一个【聊天】按钮，点击后跳转到 chat 页面

### 聊天界面

- 与业界常见的大模型聊天界面一致
- 有默认模型，可下拉切换模型
- 有输入框
- 有聊天对话历史
- 有自动的上下文管理

### 会话管理

- 支持多个会话
- 如果没有会话则自动创建
- 会话标题为初次创建的日期

### 模型选择

- 默认是文字聊天
- 也可以选择图片模型、语音模型
- 模型列表按类型（TEXT / IMAGE / TTS）分区显示，直接选择即可

### 多媒体支持

- 图片和语音文件缓存到服务器磁盘中
- 聊天界面可以直接播放语音、显示图片

## 数据库设计

### 会话表 `chat_sessions`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键 |
| title | VARCHAR(200) | 会话标题（默认为创建日期） |
| model_config_id | BIGINT | 当前使用的模型配置ID |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

### 会话历史表 `chat_messages`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 主键 |
| session_id | BIGINT (FK) | 关联会话ID |
| role | VARCHAR(20) | 角色（user / assistant） |
| content | TEXT | 消息内容 |
| model_config_id | BIGINT | 本条消息使用的模型配置ID |
| model_type | VARCHAR(20) | 模型类型（TEXT / IMAGE / TTS） |
| media_file_path | VARCHAR(500) | 媒体文件路径（图片/语音） |
| created_at | TIMESTAMP | 创建时间 |

## 技术要点

- 复用现有 `AiProvider` 接口和 `AiProviderRouter` 模型解析
- 复用现有 `ImageProvider` 和 `TtsProvider` 接口
- SSE 流式输出文字回复
- 图片文件存储路径: `data/chat/images/{sessionId}/{uuid}.png`
- 语音文件存储路径: `data/chat/audio/{sessionId}/{uuid}.mp3`
- 上下文管理: 自动携带历史消息（可设置上下文窗口大小）
