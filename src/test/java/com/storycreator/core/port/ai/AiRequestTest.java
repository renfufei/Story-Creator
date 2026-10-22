package com.storycreator.core.port.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用参数里的「项目 / 章 / 卷」：纯元数据，不能影响提示词本身。
 */
class AiRequestTest {

    @Test
    void applyScope_setsScopeWithoutTouchingPrompts() {
        AiRequest request = AiRequest.builder()
                .systemPrompt("SYS")
                .userPrompt("USER")
                .maxTokens(100)
                .build();

        request.applyScope(26L, 7, 2, "风起云涌");

        assertThat(request.getProjectId()).isEqualTo(26L);
        assertThat(request.getChapterNumber()).isEqualTo(7);
        assertThat(request.getVolumeNumber()).isEqualTo(2);
        assertThat(request.getVolumeTitle()).isEqualTo("风起云涌");
        // 关键：卷号不进正文/系统提示词
        assertThat(request.getUserPrompt()).isEqualTo("USER");
        assertThat(request.getSystemPrompt()).isEqualTo("SYS");
    }

    @Test
    void builder_canCarryScopeDirectly() {
        AiRequest request = AiRequest.builder()
                .userPrompt("X")
                .projectId(1L)
                .chapterNumber(31)
                .volumeNumber(2)
                .volumeTitle("第二卷")
                .build();

        assertThat(request.hasVolume()).isTrue();
        assertThat(request.getVolumeNumber()).isEqualTo(2);
    }

    @Test
    void hasVolume_falseWhenUnknown() {
        AiRequest request = AiRequest.builder().userPrompt("X").build();

        assertThat(request.hasVolume()).isFalse();
        assertThat(request.getVolumeNumber()).isZero();
        assertThat(request.getChapterNumber()).isZero();
    }

    @Test
    void scopeTag_isLogFriendly() {
        AiRequest request = new AiRequest();

        assertThat(request.scopeTag()).isEqualTo("P-");

        request.applyScope(26L, 7, 2, "风起云涌");
        assertThat(request.scopeTag()).isEqualTo("P26 v2 c7");

        request.applyScope(26L, 0, 0, null);
        assertThat(request.scopeTag()).isEqualTo("P26");
    }
}
