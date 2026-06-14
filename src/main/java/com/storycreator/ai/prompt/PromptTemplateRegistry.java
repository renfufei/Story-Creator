package com.storycreator.ai.prompt;

import com.storycreator.core.domain.Genre;
import com.storycreator.core.domain.WorkflowStep;
import com.storycreator.persistence.entity.PromptTemplateEntity;
import com.storycreator.persistence.repository.PromptTemplateRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

@Component
public class PromptTemplateRegistry {

    private final PromptTemplateRepository repository;

    private static final Set<String> SHORT_VARIABLES = Set.of(
            "title", "genre", "description", "chapterNumber", "totalChapters",
            "chapterTitle", "chapterWordCount", "chapterWordCountMin",
            "chapterWordCountMax", "nextChapterTitle"
    );

    public PromptTemplateRegistry(PromptTemplateRepository repository) {
        this.repository = repository;
    }

    @PostConstruct
    public void initDefaults() {
        if (repository.count() > 0) return;

        // World Building
        saveDefault(WorkflowStep.WORLD_BUILDING, null, "通用世界观模板",
                "你是一位专业的网络小说世界观架构师，擅长构建宏大而细腻的虚构世界。",
                """
                你是一位资深的网络小说世界观架构师。请根据以下信息，为这部小说创建一个完整而独特的世界观设定。

                【小说信息】
                标题：{{title}}
                题材：{{genre}}
                简介：{{description}}

                请从以下方面详细描述世界观：
                1. **世界背景**：基本设定（时代、地理、社会结构）
                2. **力量体系**：修炼/能力/科技体系的等级划分和运作规则
                3. **势力分布**：主要国家/门派/组织及其关系
                4. **历史脉络**：重要历史事件和传说
                5. **特色元素**：独特的文化、物种、道具或规则
                6. **冲突根源**：世界中存在的主要矛盾和冲突

                请确保世界观设定内在逻辑自洽，富有想象力，并为故事发展提供丰富的空间。用中文输出。
                """);

        // Character Design
        saveDefault(WorkflowStep.CHARACTER_DESIGN, null, "通用角色设计模板",
                "你是一位擅长角色塑造的网络小说作家，善于创造有深度、有魅力的角色。",
                """
                你是一位擅长角色塑造的网络小说作家。请根据以下世界观和故事信息，设计一组立体丰满的角色。

                【小说信息】
                标题：{{title}}
                题材：{{genre}}
                简介：{{description}}

                【已确定的世界观】
                {{worldSetting}}

                请设计 4-6 个主要角色，每个角色包含：
                1. **姓名**：符合世界观设定的名字
                2. **身份/职业**：在世界中的社会角色
                3. **性格特征**：2-3个核心性格特点及其成因
                4. **外貌特征**：简要但有辨识度的外貌描写
                5. **背景故事**：过往经历，与世界观的联系
                6. **动机与目标**：驱动角色行动的核心动力
                7. **角色关系**：与其他角色之间的关系网

                确保角色之间有互补和冲突，能推动剧情发展。用中文输出。
                """);

        // Outline Generation
        saveDefault(WorkflowStep.OUTLINE_GENERATION, null, "通用大纲模板",
                "你是一位经验丰富的网络小说策划，擅长构建紧凑的故事结构和引人入胜的剧情节奏。",
                """
                你是一位经验丰富的网络小说策划。请根据以下信息，生成一个引人入胜的故事大纲。

                【小说信息】
                标题：{{title}}
                题材：{{genre}}
                简介：{{description}}

                【世界观】
                {{worldSetting}}

                【主要角色】
                {{characters}}

                请生成一个包含 {{totalChapters}} 章的故事大纲，包含：

                **整体架构：**
                - 开篇引入（前20%）：介绍世界和主角，设置初始冲突
                - 发展铺垫（20-40%）：角色成长，遭遇挑战
                - 中段高潮（40-60%）：重大转折和冲突升级
                - 深入发展（60-80%）：揭示真相，角色蜕变
                - 收束结局（后20%）：最终决战和结局

                **逐章大纲：**
                每章包括：章节标题、核心事件、涉及角色、情绪基调。

                确保情节节奏合理，冲突层层递进，伏笔和呼应到位。用中文输出。
                """);

        // Chapter Writing
        saveDefault(WorkflowStep.CHAPTER_WRITING, null, "通用章节写作模板",
                "你是一位文笔出色的网络小说作家，擅长创作引人入胜的故事内容，文风流畅，描写生动。",
                """
                你是一位文笔优美的网络小说作家。请根据以下信息写作本章内容。

                【小说信息】
                标题：{{title}}
                题材：{{genre}}

                【世界观】
                {{worldSetting}}

                【主要角色】
                {{characters}}

                【总体大纲】
                {{overallOutline}}

                【本章大纲】
                第{{chapterNumber}}章：{{chapterTitle}}
                {{chapterSummary}}

                {{previousContext}}

                【下一章预告（用于在本章末尾自然衔接）】
                {{nextChapterTitle}}：{{nextChapterSummary}}

                **写作要求：**
                1. 字数要求：约{{chapterWordCount}}字（不少于{{chapterWordCountMin}}字，不超过{{chapterWordCountMax}}字）
                2. 场景描写生动，善用五感
                3. 对话自然流畅，符合角色性格
                4. 适当使用悬念和节奏控制
                5. 章末留有吸引力的钩子
                6. 文风符合{{genre}}类型的读者期待

                请直接输出小说正文，不要输出标题或章节号。用中文输出。
                """);

        // Polishing
        saveDefault(WorkflowStep.POLISHING, null, "通用润色模板",
                "你是一位资深的网络小说编辑，擅长文字润色、情节优化和节奏把控。",
                """
                你是一位资深的网络小说编辑。请对以下章节内容进行润色修改。

                【小说信息】
                标题：{{title}}
                题材：{{genre}}

                【原文内容】
                {{content}}

                {{polishNote}}

                **润色要求：**
                1. 修正错别字和语病
                2. 优化句式结构，使文笔更加流畅
                3. 增强场景描写的画面感
                4. 改善对话的自然度和角色辨识度
                5. 调整节奏，确保张弛有度
                6. 检查逻辑是否自洽
                7. 保持原文风格和作者意图不变

                请输出润色后的完整正文。用中文输出。
                """);
    }

    private void saveDefault(WorkflowStep step, Genre genre, String name, String systemPrompt, String template) {
        PromptTemplateEntity entity = new PromptTemplateEntity();
        entity.setStep(step);
        entity.setGenre(genre);
        entity.setName(name);
        entity.setSystemPrompt(systemPrompt);
        entity.setTemplate(template);
        entity.setDefaultTemplate(template);
        entity.setDefaultSystemPrompt(systemPrompt);
        entity.setDefault(true);
        repository.save(entity);
    }

    public String getTemplate(WorkflowStep step, Genre genre) {
        return repository.findByStepAndGenreAndIsDefaultTrue(step, genre)
                .or(() -> repository.findByStepAndGenreIsNullAndIsDefaultTrue(step))
                .map(PromptTemplateEntity::getTemplate)
                .orElseThrow(() -> new IllegalStateException("No template found for step: " + step));
    }

    public String getSystemPrompt(WorkflowStep step, Genre genre) {
        return repository.findByStepAndGenreAndIsDefaultTrue(step, genre)
                .or(() -> repository.findByStepAndGenreIsNullAndIsDefaultTrue(step))
                .map(PromptTemplateEntity::getSystemPrompt)
                .orElse(null);
    }

    public String resolveTemplate(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String value = entry.getValue() != null ? entry.getValue() : "";
            if (!SHORT_VARIABLES.contains(entry.getKey()) && !value.isEmpty() && value.length() > 50) {
                value = wrapWithDelimiters(value);
            }
            result = result.replace("{{" + entry.getKey() + "}}", value);
        }
        return result;
    }

    /**
     * Sanitize backticks in content and wrap with triple-backtick delimiters.
     */
    private String wrapWithDelimiters(String content) {
        String sanitized = content.replace('`', '\uff40'); // replace ` with fullwidth ｀
        return "\n```\n" + sanitized + "\n```\n";
    }
}
