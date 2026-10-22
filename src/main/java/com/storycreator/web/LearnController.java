package com.storycreator.web;

import com.storycreator.ai.router.AiProviderRouter;
import com.storycreator.ai.router.TtsProviderRegistry;
import com.storycreator.learn.CetWordBank;
import com.storycreator.learn.IdiomBank;
import com.storycreator.learn.LearnAudioService;
import com.storycreator.learn.MiniprogramExporter;
import com.storycreator.learn.MultiplicationFormula;
import com.storycreator.learn.WordMatchBank;
import com.storycreator.learn.WordRelationBank;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
public class LearnController {

    private final LearnAudioService learnAudioService;
    private final TtsProviderRegistry ttsProviderRegistry;
    private final AiProviderRouter providerRouter;
    private final WordMatchBank wordMatchBank;
    private final CetWordBank cetWordBank;
    private final IdiomBank idiomBank;
    private final WordRelationBank wordRelationBank;
    private final MiniprogramExporter miniprogramExporter;

    public LearnController(LearnAudioService learnAudioService, TtsProviderRegistry ttsProviderRegistry,
                           AiProviderRouter providerRouter, WordMatchBank wordMatchBank,
                           CetWordBank cetWordBank, IdiomBank idiomBank,
                           WordRelationBank wordRelationBank, MiniprogramExporter miniprogramExporter) {
        this.learnAudioService = learnAudioService;
        this.ttsProviderRegistry = ttsProviderRegistry;
        this.providerRouter = providerRouter;
        this.wordMatchBank = wordMatchBank;
        this.cetWordBank = cetWordBank;
        this.idiomBank = idiomBank;
        this.wordRelationBank = wordRelationBank;
        this.miniprogramExporter = miniprogramExporter;
    }

    @GetMapping("/learn")
    public String learnIndex() {
        return "forward:/pages/learn.html";
    }

    @GetMapping("/learn/multiplication")
    public String multiplication() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        return "forward:/pages/learn-multiplication.html";
    }

    // ==================== 英语单词匹配 ====================

    @GetMapping("/learn/word-match")
    public String wordMatch() {
        return "forward:/pages/learn-word-match.html";
    }

    /**
     * 单词匹配引导数据：全部册次 + 每册按主题切好的关卡（含全部单词对）。
     *
     * <p>一次性下发（人教版 24 册，gzip 约 116KB），换来切换册次 / 关卡零请求、离线可用；
     * 进度由前端 localStorage 维护，无需服务端状态。
     *
     * <p>{@code extraBooks} 是<b>大学</b>那两册的元信息（id / 名称 / 关数 / 词数）：它属于独立词源，
     * 全部关卡另有约 225KB（gzip），塞进这份首屏数据会把同步 XHR 的等待时间拉长两倍，所以只带元信息，
     * 关卡等用户真的选到大学时再走 {@link #wordMatchCet()}。
     */
    @GetMapping("/learn/word-match/data")
    @ResponseBody
    public Map<String, Object> wordMatchData() {
        Map<String, Object> data = new LinkedHashMap<>(wordMatchBank.bootstrapData());
        data.put("extraBooks", cetWordBank.bookIndex());
        return data;
    }

    /**
     * 大学词库（独立词源）：2 册（四级 7508 条 / 六级 5651 条）+ 切好的关卡。
     * 结构与 {@link #wordMatchData()} 完全一致，前端合并进同一份数据后无差别使用。
     */
    @GetMapping("/learn/word-match/cet")
    @ResponseBody
    public Map<String, Object> wordMatchCet() {
        return cetWordBank.bootstrapData();
    }

    /**
     * 导出小节：打包一个可直接导入微信开发者工具的小程序工程。
     *
     * <p>内容是「模板 + 现场算出来的词库」：模板在 {@code src/main/resources/miniprogram-template/}，
     * 词库由 {@link MiniprogramExporter} 用与网页同一条切关路径现算，所以导出的永远是最新词库。
     * 这也是换机器/重建小程序源码的唯一入口 —— 仓库里不再保存第二份词库。
     */
    @GetMapping("/learn/word-match/miniprogram.zip")
    public ResponseEntity<byte[]> wordMatchMiniprogram() {
        byte[] zip = miniprogramExporter.build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"word-pair-miniprogram.zip\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(zip.length)
                .body(zip);
    }

    @GetMapping("/learn/multiplication/settings")
    public String multiplicationSettings() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);
        return "forward:/pages/learn-multiplication-settings.html";
    }

    /**
     * 乘法口诀音频管理页引导数据：TTS 配置 + 默认配置 + 口诀/前缀（供算式映射）+ 音频总数。
     *
     * <p>{@code extraParams} 原样回传 JSON 字符串（与原 Thymeleaf 序列化一致），由前端自行解析
     * 出 {@code voices} / {@code format}。
     */
    @GetMapping("/learn/multiplication/settings/data")
    @ResponseBody
    public Map<String, Object> multiplicationSettingsData() {
        learnAudioService.ensureFormulaRecordsExist(MultiplicationFormula.MODULE);

        List<Map<String, Object>> configs = ttsProviderRegistry.getActiveTtsConfigs().stream()
                .map(c -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("id", c.getId());
                    m.put("displayName", c.getDisplayName());
                    m.put("modelId", c.getModelId());
                    m.put("extraParams", c.getExtraParams());
                    return m;
                })
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("ttsConfigs", configs);
        result.put("defaultTtsConfigId", providerRouter.getGlobalDefaultTtsConfigId());
        result.put("formulas", MultiplicationFormula.FORMULAS);
        result.put("prefixes", MultiplicationFormula.PREFIXES);
        result.put("totalAudioCount", MultiplicationFormula.TOTAL_AUDIO_COUNT);
        return result;
    }

    // ==================== 成语填空 ====================

    /**
     * 成语填空。整页自包含（题库由 {@code /learn/idiom-fill/data} 一次性下发），
     * 进度存 localStorage，没有服务端状态。
     */
    @GetMapping("/learn/idiom-fill")
    public String idiomFill() {
        return "forward:/pages/learn-idiom-fill.html";
    }

    /**
     * 成语填空引导数据：三学段（小学 / 初中 / 高中）+ 每段的关卡（每关 4~8 条成语，多为 6 条）。
     *
     * <p>一次下发 180 条成语（含释义与出处），gzip 后约 20KB —— 比单词匹配小一个量级，
     * 所以不拆多次请求：切学段、切关卡零等待。
     */
    @GetMapping("/learn/idiom-fill/data")
    @ResponseBody
    public Map<String, Object> idiomFillData() {
        return idiomBank.bootstrapData();
    }

    // ==================== 近义词 / 反义词匹配 ====================

    /**
     * 近义词匹配。与反义词匹配共用同一张页面（{@code learn-word-relation.html}），
     * 由前端按 {@code location.pathname} 判断模式，再向 {@link #wordRelationData(String)} 取词库。
     *
     * <p>两者做成一张页而不是两张：交互（左题面、右答案、点两边配对）与统计逻辑完全一致，
     * 差别只有词库与文案；拆成两份等于把 900 行 JS 复制一遍，后续改一处必须记得改两处。
     */
    @GetMapping("/learn/synonym-match")
    public String synonymMatch() {
        return "forward:/pages/learn-word-relation.html";
    }

    @GetMapping("/learn/antonym-match")
    public String antonymMatch() {
        return "forward:/pages/learn-word-relation.html";
    }

    /**
     * 近义词 / 反义词词库：{@code mode=synonym|antonym}，缺省 synonym。
     *
     * <p>每个模式 3 学段 × 10 主题 × 18 对 = 540 对（90 关），含辨析；gzip 后三十几 KB，
     * 一次下发后切学段 / 切关卡零请求，进度存 localStorage。
     */
    @GetMapping("/learn/word-relation/data")
    @ResponseBody
    public Map<String, Object> wordRelationData(
            @RequestParam(name = "mode", defaultValue = WordRelationBank.MODE_SYNONYM) String mode) {
        String normalized = WordRelationBank.normalizeMode(mode);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode 只能是 " + String.join(" / ", WordRelationBank.modes()));
        }
        return wordRelationBank.bootstrapData(normalized);
    }

    // ==================== 俄罗斯方块（Block Blast 网页版） ====================

    /**
     * 俄罗斯方块。整页由 {@code ../BlockBlast/index.html} 原样迁入：游戏逻辑、样式、存档
     * （localStorage 的 {@code blockblast_save} / {@code blockblast_hs}）全部自包含，
     * 不需要服务端数据，所以这里只是一个 forward，没有 {@code /data} 端点。
     *
     * <p>页面属于「沉浸式全屏页」（自带 flex 布局 + overflow:hidden），不挂站点导航栏，
     * 返回教学模块首页靠页内设置菜单里的「返回」按钮。
     */
    @GetMapping("/learn/block-blast")
    public String blockBlast() {
        return "forward:/pages/learn-block-blast.html";
    }

    // ==================== 数独 ====================

    /**
     * 数独（3×3 ~ 9×9 / 三档难度）。
     *
     * <p>与成语填空等模块不同，这里**没有 {@code /data} 端点**：数独的题目不该是题库 ——
     * 每局现场用「随机终盘 + 唯一解校验」算出来（最慢的 9×9 困难也就十几毫秒），
     * 进度与存档全在 localStorage。多挂一个接口只会多一次白屏等待。
     *
     * <p>玩法细节（为什么 3/5/7 只有拉丁方规则）见页面里的注释与【规则说明】弹层。
     */
    @GetMapping("/learn/sudoku")
    public String sudoku() {
        return "forward:/pages/learn-sudoku.html";
    }
}
