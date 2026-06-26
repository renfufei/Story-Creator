package com.storycreator.tts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storycreator.ai.provider.OpenAiTtsProvider;
import com.storycreator.core.port.tts.TtsRequest;
import com.storycreator.core.service.GlobalSettingService;
import com.storycreator.tts.Mp3QualityDetector.QualityResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TTS 重复音检测测试：逐个调用 TTS 生成单字音频，用 Mp3QualityDetector 检测重复音。
 *
 * 运行方式:
 * mvn test -Dtest=TtsRepeatDetectionTest \
 *   -Dtts.baseUrl=http://localhost:8000 \
 *   -Dtts.apiKey=111111 \
 *   -Dtts.voice=serena \
 *   -Dtts.model=Qwen3-TTS-12Hz-1.7B-CustomVoice-bf16
 */
@EnabledIf("ttsConfigured")
class TtsRepeatDetectionTest {

    static final String BASE_URL = System.getProperty("tts.baseUrl", "http://localhost:8000");
    static final String API_KEY = System.getProperty("tts.apiKey", "111111");
    static final String VOICE = System.getProperty("tts.voice", "serena");
    static final String MODEL = System.getProperty("tts.model", "Qwen3-TTS-12Hz-1.7B-CustomVoice-bf16");

    static final List<String> CHARACTERS = List.copyOf(new LinkedHashSet<>(List.of(
            "啊", "呀", "哎", "唉", "哦", "噢", "喔", "哟", "呦", "嗯", "唔", "呃", "嗝",
            "咦", "嘿", "嗨", "哈", "嘻", "哼", "嗤", "噗", "咳", "呸", "啐", "唾", "呼",
            "吁", "咝", "嘶", "嘘", "咄", "嚯", "嗬", "嚄", "噫", "嚱", "唏", "咿", "呜",
            "哇", "哗", "嘟", "咕", "噜", "咯", "咔", "咚", "咣", "当", "叮", "咛", "铃",
            "嗖", "嗡", "嗒", "嘀", "嘭", "嘣", "砰", "啪", "噼", "嘎", "嘠", "嘞", "喵",
            "汪", "咩", "哞", "嗷", "啾", "唧", "喳", "嘎", "呱", "嗥", "嚎", "咴", "喘",
            "嗳", "嗽", "喷", "嚏", "啜", "嘬", "咂", "吧", "叭", "嗍", "咬", "嚼", "咀",
            "咽", "呷", "啖", "哧"
    )));

    private OpenAiTtsProvider ttsProvider;
    private Mp3QualityDetector qualityDetector;

    static boolean ttsConfigured() {
        return System.getProperty("tts.baseUrl") != null;
    }

    @BeforeEach
    void setUp() {
        GlobalSettingService globalSettingService = mock(GlobalSettingService.class);
        when(globalSettingService.getAiTimeoutSeconds()).thenReturn(300);

        ttsProvider = new OpenAiTtsProvider(new ObjectMapper(), globalSettingService);
        qualityDetector = new Mp3QualityDetector(new Mp3ProcessingService());
    }

    @Test
    void detectRepeatCharacters() {
        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        System.out.println("\n========== TTS 重复音检测开始 ==========");
        System.out.printf("配置: baseUrl=%s, voice=%s, model=%s%n", BASE_URL, VOICE, MODEL);
        System.out.printf("测试字符数: %d (去重后)%n%n", CHARACTERS.size());

        for (String ch : CHARACTERS) {
            try {
                TtsRequest request = TtsRequest.builder()
                        .model(MODEL)
                        .input(ch)
                        .voice(VOICE)
                        .responseFormat("mp3")
                        .speed(1.0)
                        .baseUrl(BASE_URL)
                        .apiKey(API_KEY)
                        .build();

                byte[] audio = ttsProvider.generateAudio(request);
                QualityResult result = qualityDetector.analyze(audio);

                if (result.passed()) {
                    passed.add(ch);
                    System.out.printf("  [PASS] %s%n", ch);
                } else {
                    failed.add(ch + " → " + result.issue());
                    System.out.printf("  [FAIL] %s → %s%n", ch, result.issue());
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                // Truncate long messages
                if (msg.length() > 100) {
                    msg = msg.substring(0, 100) + "...";
                }
                errors.add(ch + " → " + msg);
                System.out.printf("  [ERROR] %s → %s%n", ch, msg);
            }
        }

        // 汇总报告
        System.out.println("\n========== TTS 重复音检测报告 ==========");
        System.out.printf("配置: baseUrl=%s, voice=%s, model=%s%n", BASE_URL, VOICE, MODEL);
        System.out.printf("测试字符数: %d (去重后)%n", CHARACTERS.size());

        System.out.printf("%n--- 正常生成 (%d个) ---%n", passed.size());
        System.out.println(String.join(", ", passed));

        System.out.printf("%n--- 检测到重复音 (%d个) ---%n", failed.size());
        for (String f : failed) {
            System.out.println(f);
        }

        System.out.printf("%n--- API调用失败 (%d个) ---%n", errors.size());
        for (String e : errors) {
            System.out.println(e);
        }

        System.out.printf("%n总计: 正常%d个, 重复音%d个, 失败%d个%n", passed.size(), failed.size(), errors.size());
        System.out.println("=======================================\n");
    }
}
