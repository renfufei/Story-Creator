package com.storycreator.ai.provider;

import com.storycreator.core.port.ai.AiProvider;
import com.storycreator.core.port.ai.AiRequest;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Mock AI provider for testing. Returns format-appropriate content based on prompt keywords.
 * Provider name: "mock". No external dependencies.
 */
@Component
public class MockAiProvider implements AiProvider {

    @Override
    public String getProviderName() {
        return "mock";
    }

    @Override
    public String generateText(AiRequest request) {
        String combined = combinedPrompt(request);
        PromptCategory category = classify(combined);
        return generateContent(category, combined);
    }

    @Override
    public Flux<String> streamText(AiRequest request) {
        String content = generateText(request);
        return Flux.fromStream(
                IntStream.range(0, (content.length() + 4) / 5)
                        .mapToObj(i -> content.substring(i * 5, Math.min((i + 1) * 5, content.length())))
        ).delayElements(Duration.ofMillis(1));
    }

    // --- Public for MockApiController ---

    public String combinedPrompt(AiRequest request) {
        return (request.getSystemPrompt() != null ? request.getSystemPrompt() : "")
                + " " + (request.getUserPrompt() != null ? request.getUserPrompt() : "");
    }

    public String generateForPrompt(String combinedPrompt) {
        PromptCategory category = classify(combinedPrompt);
        return generateContent(category, combinedPrompt);
    }

    // --- Classification ---

    enum PromptCategory {
        WORLD_BUILDING,
        CHARACTER_OVERVIEW,
        CHARACTER_CARD,
        CHARACTER_REFINE,
        STORY_SUMMARY,
        CHAPTER_OUTLINE,
        CHAPTER_OUTLINE_REFINE,
        CHAPTER_WRITING,
        POLISHING,
        PROOFREADING_REPORT,
        PROOFREAD_FIX,
        CHAPTER_TITLE,
        CHARACTER_STATES,
        VOLUME_ARC,
        PROOFREAD_PLOT_SUMMARY,
        PROOFREAD_CHARACTER_CHECK,
        PROOFREAD_CONSISTENCY,
        PROOFREAD_CONTINUITY,
        PROOFREAD_FORESHADOWING,
        GENERIC
    }

    PromptCategory classify(String combined) {
        // Priority-ordered classification
        // 1. Proofread fix (highest priority)
        if (combined.contains("校对修复") || combined.contains("PROOFREAD_FIX")) {
            return PromptCategory.PROOFREAD_FIX;
        }
        // 2. Plot summary
        if (combined.contains("情节摘要") || combined.contains("主要情节摘要")) {
            return PromptCategory.PROOFREAD_PLOT_SUMMARY;
        }
        // 3. Character name check
        if (combined.contains("角色名单") || combined.contains("人物姓名")) {
            return PromptCategory.PROOFREAD_CHARACTER_CHECK;
        }
        // 4. Consistency check
        if ((combined.contains("一致性") && combined.contains("角色")) || combined.contains("前一章情节摘要")) {
            return PromptCategory.PROOFREAD_CONSISTENCY;
        }
        // 5. Continuity check
        if (combined.contains("上章结尾") || combined.contains("衔接检查")) {
            return PromptCategory.PROOFREAD_CONTINUITY;
        }
        // 6. Foreshadowing
        if (combined.contains("伏笔") || combined.contains("悬念")) {
            return PromptCategory.PROOFREAD_FORESHADOWING;
        }
        // 7. Chapter title
        if (combined.contains("简短的章节标题") || (combined.contains("章节标题") && combined.contains("4-12"))) {
            return PromptCategory.CHAPTER_TITLE;
        }
        // 8. Character states
        if (combined.contains("角色状态") || (combined.contains("当前状态") && combined.contains("角色名"))) {
            return PromptCategory.CHARACTER_STATES;
        }
        // 9. Volume arc
        if (combined.contains("卷弧线") || combined.contains("卷故事弧线") || combined.contains("volumeNumber")) {
            return PromptCategory.VOLUME_ARC;
        }
        // 10. World building
        if (combined.contains("世界观") || combined.contains("世界设定")) {
            return PromptCategory.WORLD_BUILDING;
        }
        // 11. Character overview
        if (combined.contains("角色总览") || combined.contains("CHARACTER_OVERVIEW")) {
            return PromptCategory.CHARACTER_OVERVIEW;
        }
        // 12. Character card
        if (combined.contains("角色卡") || combined.contains("CHARACTER_CARD") || combined.contains("cardNumber")) {
            return PromptCategory.CHARACTER_CARD;
        }
        // 13. Character refine
        if ((combined.contains("精修") || combined.contains("REFINE")) && combined.contains("角色")) {
            return PromptCategory.CHARACTER_REFINE;
        }
        // 14. Story summary
        if (combined.contains("故事总纲") || combined.contains("STORY_SUMMARY")) {
            return PromptCategory.STORY_SUMMARY;
        }
        // 15. Chapter outline refine
        if ((combined.contains("章节大纲") || combined.contains("CHAPTER_OUTLINE"))
                && (combined.contains("精修") || combined.contains("REFINE"))) {
            return PromptCategory.CHAPTER_OUTLINE_REFINE;
        }
        // 16. Chapter outline
        if (combined.contains("章节大纲") || combined.contains("CHAPTER_OUTLINE")) {
            return PromptCategory.CHAPTER_OUTLINE;
        }
        // 17. Chapter writing
        if (combined.contains("写作") || combined.contains("chapterWordCount")) {
            return PromptCategory.CHAPTER_WRITING;
        }
        // 18. Polishing
        if (combined.contains("润色") || combined.contains("修改意见")) {
            return PromptCategory.POLISHING;
        }
        // 19. Proofreading report (fallback)
        if (combined.contains("校对") || combined.contains("proofreading")) {
            return PromptCategory.PROOFREADING_REPORT;
        }
        // 20. Generic fallback
        return PromptCategory.GENERIC;
    }

    // --- Content Generation ---

    private String generateContent(PromptCategory category, String combinedPrompt) {
        return switch (category) {
            case WORLD_BUILDING -> generateWorldBuilding();
            case CHARACTER_OVERVIEW -> generateCharacterOverview();
            case CHARACTER_CARD -> generateCharacterCard();
            case CHARACTER_REFINE -> generateCharacterRefine();
            case STORY_SUMMARY -> generateStorySummary();
            case CHAPTER_OUTLINE -> generateChapterOutlines();
            case CHAPTER_OUTLINE_REFINE -> generateChapterOutlineRefine();
            case CHAPTER_WRITING -> generateChapterContent(combinedPrompt);
            case POLISHING -> generatePolishedContent(combinedPrompt);
            case PROOFREADING_REPORT -> generateProofreadingReport();
            case PROOFREAD_FIX -> generateProofreadFix(combinedPrompt);
            case CHAPTER_TITLE -> generateChapterTitle();
            case CHARACTER_STATES -> generateCharacterStates();
            case VOLUME_ARC -> generateVolumeArc();
            case PROOFREAD_PLOT_SUMMARY -> generatePlotSummary();
            case PROOFREAD_CHARACTER_CHECK -> generateCharacterCheck();
            case PROOFREAD_CONSISTENCY -> generateConsistencyCheck();
            case PROOFREAD_CONTINUITY -> generateContinuityCheck();
            case PROOFREAD_FORESHADOWING -> generateForeshadowing();
            case GENERIC -> generateGeneric();
        };
    }

    private int extractWordCountTarget(String text) {
        Matcher m = Pattern.compile("约(\\d+)字").matcher(text);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return 3000;
    }

    private String generateWorldBuilding() {
        return "这是一个架空的修仙世界，名为「天元大陆」。大陆分为东西南北四大域，" +
                "每域各有数十个大小宗门。灵气充沛之地多有洞天福地，是修士们趋之若鹜的修炼圣地。" +
                "天元大陆的修炼体系分为筑基、金丹、元婴、化神、合体、大乘六大境界。" +
                "每个境界又分初期、中期、后期三个小阶段。凡人若想踏上修仙之路，首先需要拥有灵根。" +
                "灵根分为金木水火土五行，拥有单一灵根者天资最佳，双灵根次之。" +
                "大陆上最强大的势力是「天道院」，坐落于大陆中央的天柱山巅，掌控着整个修仙界的秩序。";
    }

    private String generateCharacterOverview() {
        return "本书主要角色共五位，分别代表不同的立场和性格。" +
                "主角陈轩是一个出身平凡的少年，拥有罕见的混沌灵根。" +
                "女主林雪瑶出身名门望族，性格清冷但内心善良。" +
                "反派萧无极天资卓绝却心术不正，是主角前进道路上的最大阻碍。" +
                "师父李道真是一位隐世高人，对主角亦师亦父。" +
                "兄弟王铁柱虽然天赋平平，却忠心耿耿陪伴主角闯荡天下。";
    }

    private String generateCharacterCard() {
        return """
                【姓名】陈轩
                【性别】男
                【年龄】十八岁
                【身份】混沌灵根修士
                【性格】坚韧不拔，志向远大，沉稳内敛
                【外貌】身材修长，眉宇间有一股不服输的锐气，目光坚毅
                【背景】出身贫寒村落，偶得上古传承，从此走上逆天改命之路
                【动机】突破极限，护佑至亲，追寻大道真谛
                【能力】混沌灵力，可融合五行之力，攻防兼备
                【关系】林雪瑶（知己），王铁柱（兄弟），李道真（恩师），萧无极（宿敌）""";
    }

    private String generateCharacterRefine() {
        return """
                【姓名】陈轩
                【性别】男
                【年龄】十八岁
                【身份】混沌灵根修士，天道院外门弟子
                【性格】坚韧不拔，志向远大，沉稳内敛，对友人重情重义
                【外貌】身材修长挺拔，剑眉星目，眉宇间有一股不服输的锐气，笑起来令人如沐春风
                【背景】出身东域偏僻村落，幼年父母失踪，由祖父抚养长大。十五岁觉醒混沌灵根，获上古传承入天道院
                【动机】寻找失踪双亲的下落，突破极限证道长生，护佑身边珍视之人
                【能力】混沌灵力可融合五行，剑道天赋极高，悟性超凡，战斗中善于以弱胜强
                【关系】林雪瑶（红颜知己），王铁柱（生死兄弟），李道真（授业恩师），萧无极（宿敌）
                【概要】陈轩是一位出身平凡却天赋异禀的少年修士。他性格坚韧沉稳，面对逆境从不退缩。拥有罕见的混沌灵根使他在修炼一途上潜力无限，但也因此招来强敌觊觎。他的成长之路充满艰辛与机遇，每一步都在证明出身不能决定命运。""";
    }

    private String generateStorySummary() {
        return "本书讲述了主角从一介凡人成长为大陆顶尖强者的传奇故事。" +
                "全书分为三大卷：第一卷讲述主角获得传承、拜师学艺的成长期；" +
                "第二卷讲述主角参加宗门大比、崭露头角的崛起期；" +
                "第三卷讲述主角面对大陆危机、力挽狂澜的巅峰期。" +
                "主线围绕主角与反派的对抗展开，感情线作为辅助推动角色成长。" +
                "全书基调积极向上，节奏张弛有度，高潮迭起。";
    }

    private String generateChapterOutlines() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            sb.append("[[SECTION:").append(i).append("]]\n");
            sb.append("**标题：**").append(getChapterTitleByIndex(i)).append("\n\n");
            sb.append("**出场角色：**陈轩、");
            sb.append(i % 3 == 0 ? "萧无极、李道真" : i % 3 == 1 ? "林雪瑶、王铁柱" : "李道真、林雪瑶");
            sb.append("\n\n");
            sb.append("本章讲述主角在修炼路上遇到的第").append(i).append("个挑战。");
            sb.append("情节推进：主角在").append(i % 3 == 0 ? "突破瓶颈" : i % 3 == 1 ? "遭遇敌手" : "获得机缘");
            sb.append("的过程中展现出坚韧品质，通过智慧与毅力克服困难，同时伏笔后续剧情发展。");
            sb.append("本章结尾设置悬念，为下一章的展开做好铺垫。");
            sb.append("\n\n");
        }
        return sb.toString();
    }

    private String generateChapterOutlineRefine() {
        return """
                **标题：**命运初启

                **出场角色：**陈轩、林雪瑶、王铁柱

                本章讲述主角陈轩在宗门大比前夕的准备与心理变化。清晨修炼突破瓶颈，获得混沌灵力初步掌控。\
                与林雪瑶在演武场偶遇，两人因切磋产生默契。王铁柱带来消息：萧无极已放话要在大比中给陈轩一个教训。\
                陈轩不为所动，继续潜心修炼。章末暗示李道真在暗中关注弟子的成长，为后续师徒互动埋下伏笔。""";
    }

    private String generateChapterContent(String combinedPrompt) {
        int target = extractWordCountTarget(combinedPrompt);
        StringBuilder sb = new StringBuilder();
        sb.append("清晨的阳光透过窗棂洒落，陈轩缓缓睁开双眼。一夜的修炼让他体内灵力又充盈了几分。\n\n");
        sb.append("「今日便是宗门大比的日子了。」他喃喃自语，起身整理衣衫。\n\n");
        sb.append("窗外传来喧闹声，师兄师姐们已经开始在演武场集合。陈轩深吸一口气，将心中的忐忑压下，大步走出门去。\n\n");
        sb.append("演武场上人头攒动，各峰弟子齐聚一堂。主持大比的是掌门座下首席长老张元化，此人修为深不可测，据说已达化神中期。\n\n");
        sb.append("「诸位弟子，今日大比关乎各峰名额分配，望各位全力以赴，不可懈怠！」张元化的声音如洪钟大吕，震荡在每个人的耳畔。\n\n");
        sb.append("陈轩环顾四周，目光与萧无极对上。后者嘴角微扬，似笑非笑地看着他，眼中满是轻蔑。\n\n");
        sb.append("「陈轩师弟，听说你最近突破了金丹期？恭喜恭喜。不过在大比中，仅凭境界可不够。」萧无极的话传入耳中，让周围不少弟子窃窃私语。\n\n");
        sb.append("陈轩不为所动，淡淡道：「多谢师兄关心，胜负自有分晓。」\n\n");
        sb.append("比斗很快开始。第一场，陈轩对阵的是一位内门弟子。对方出手凌厉，招招直取要害，显然没把这个外门出身的对手放在眼里。\n\n");
        sb.append("但陈轩体内的混沌灵力何等霸道，仅仅三招，便将对方震退数丈。这一幕让全场安静了片刻，随即爆发出一阵惊呼。\n\n");
        sb.append("林雪瑶站在远处，目光落在陈轩身上，嘴角微微上扬。她早就知道这个看似平凡的少年绝非池中之物。\n\n");
        sb.append("第二场比斗更加激烈。对手是排名前十的内门弟子赵云飞，金丹后期的修为比陈轩高出一个小境界。\n\n");
        sb.append("赵云飞一出手便是本命法术「烈焰焚天」，滚滚火浪向陈轩席卷而去。观众纷纷后退，生怕被波及。\n\n");
        sb.append("陈轩面色沉静，体内混沌灵力涌出，在身前凝聚成一面灰色光盾。火浪撞上光盾，发出震耳欲聋的轰鸣，却无法前进分毫。\n\n");
        sb.append("「这是什么功法？」赵云飞面色大变。他的烈焰焚天向来无坚不摧，今日却被一个金丹初期的弟子轻易挡下。\n\n");
        sb.append("陈轩不给对方喘息之机，混沌灵力化作数道灰色光芒，如灵蛇般缠绕向赵云飞。后者连连后退，却终究躲避不及，被光芒缠住了双臂。\n\n");
        sb.append("「我认输！」赵云飞咬牙说道，脸上满是不甘。\n\n");
        sb.append("全场哗然。一个外门弟子，金丹初期的修为，竟然连胜两场，打败了内门排名前十的高手？这简直是闻所未闻。\n\n");
        sb.append("萧无极的眼中闪过一丝阴霾。他原本以为陈轩不过是运气好罢了，如今看来，此人确有几分实力。不过也仅此而已。\n\n");
        sb.append("「有意思。」萧无极低声道，嘴角勾起一抹冷笑，「希望你能撑到与我对阵。」\n\n");

        // Fill to target word count
        String[] fillers = {
                "午后的阳光渐渐西斜，比斗仍在继续。陈轩连续取胜，在众人的注目中一路高歌猛进。每一场胜利都让他的信心更加坚定，也让更多人开始正视这个外门弟子的实力。\n\n",
                "王铁柱在场边挥舞着拳头为好兄弟加油，脸上的兴奋之色溢于言表。他就知道，陈轩绝不会让他失望。\n\n",
                "休息间隙，李道真的声音忽然在陈轩心中响起：「不错，但不可大意。萧无极的实力远超你所见，他一直在隐藏底牌。」\n\n",
                "陈轩微微点头，目光投向远处那个气质出尘的白衣青年。他知道，今日的最终一战，才是真正的考验。\n\n",
                "夕阳如血，染红了半边天际。演武场上只剩最后两人：陈轩与萧无极。所有观众屏住呼吸，等待着这场巅峰对决。\n\n",
                "萧无极缓步走上擂台，白衣胜雪，长发飘飘，宛如谪仙降世。但他眼中的冷意却让人不寒而栗。\n\n",
                "「陈轩，我承认你有些本事。」萧无极淡淡道，「但你永远不会明白，天才与凡人之间的差距有多大。」\n\n",
                "陈轩深吸一口气，体内灵力运转至巅峰。混沌灵力在经脉中奔涌，发出低沉的嗡鸣。他抬起头，目光如炬。\n\n",
                "「那就让我来告诉你，」陈轩一字一句说道，「这个世界上没有不可逾越的差距。」\n\n",
                "两人同时动了。萧无极的天灵之力化作万道剑芒，铺天盖地地压向陈轩。陈轩的混沌灵力则凝聚成一柄虚幻长剑，迎着剑芒直冲而上。\n\n",
                "轰！灵力碰撞产生的冲击波将擂台震得龟裂，周围的防护法阵嗡嗡作响。两人各退三步，不分上下。\n\n",
                "萧无极面色微变。他没想到对方竟能接住自己全力一击。看来这个混沌灵根确实名不虚传。\n\n"
        };

        int idx = 0;
        while (sb.length() < target) {
            sb.append(fillers[idx % fillers.length]);
            idx++;
        }

        return sb.toString();
    }

    private String generatePolishedContent(String combinedPrompt) {
        int target = extractWordCountTarget(combinedPrompt);
        StringBuilder sb = new StringBuilder();
        sb.append("晨曦微露，第一缕金色光芒穿透薄雾，如同一柄剑划破沉寂。陈轩的呼吸绵长而均匀，体内灵力如涓涓细流般循环不息。\n\n");
        sb.append("他缓缓睁眼，瞳孔中一闪而逝的紫金色光芒转瞬即逝。又是一夜苦修，却让他离那看似不可逾越的屏障更近了一步。\n\n");
        sb.append("「今日，就是决定命运的时刻。」陈轩低声说道，语气平静得如同深潭止水。但只有他自己知道，平静水面之下是怎样的暗流汹涌。\n\n");
        sb.append("窗外的喧嚣渐起，如潮水般涌来。他整理好衣衫，迈着沉稳的步伐走向演武场。每一步都踏得坚实有力，仿佛在向天地宣告他的决心。\n\n");
        sb.append("演武场上已是人山人海。各峰弟子身着不同颜色的道袍，将偌大的广场染成一片五彩斑斓。空气中弥漫着若有若无的灵气波动，那是无数修士暗自运功所致。\n\n");
        sb.append("首席长老张元化立于高台之上，目光如电扫过全场。他的声音不大，却清晰地传入每个人耳中：「宗门大比，现在开始。」\n\n");
        sb.append("陈轩深吸一口气，将杂念尽数排出。他的目光穿过人群，与远处萧无极的视线短暂交汇。那一瞬间，无声的火花在两人之间迸溅。\n\n");
        sb.append("今日之后，一切都将不同。\n\n");

        String[] fillers = {
                "风从东方吹来，带着修炼场特有的灵气芬芳。陈轩闭目感受着周身灵力的流动，心境愈发通透。他知道，真正的强者从不畏惧挑战，而是将每一次考验都视为蜕变的契机。\n\n",
                "远处的林雪瑶一袭白裙，如雪中寒梅般清冷出尘。她的目光偶尔落在陈轩身上，眼中有一丝旁人难以察觉的柔软。这个少年身上有一种独特的气质，让她情不自禁地想要多看几眼。\n\n",
                "王铁柱挤在人群中，拼命向陈轩挥手。他虽然修为平平，但对好兄弟的支持却是发自肺腑。在这个弱肉强食的修仙界，这份纯粹的情义显得格外珍贵。\n\n",
                "擂台上的战斗一触即发。陈轩踏前一步，混沌灵力在掌心凝聚，散发出淡淡的灰色光芒。对面的对手面色凝重——他已经听说了陈轩此前的战绩，不敢有丝毫大意。\n\n"
        };

        int idx = 0;
        while (sb.length() < target) {
            sb.append(fillers[idx % fillers.length]);
            idx++;
        }

        return sb.toString();
    }

    private String generateProofreadingReport() {
        return "【校对报告】\n" +
                "1. 第3段存在一处用词重复，「缓缓」出现两次，建议替换第二处为「徐徐」。\n" +
                "2. 第5段逻辑衔接稍显突兀，建议添加过渡句增强流畅感。\n" +
                "3. 人物对话部分标点使用规范，无明显错误。\n" +
                "4. 全章节奏控制良好，无明显拖沓之处。\n" +
                "总体评价：本章质量较高，仅需微调即可。";
    }

    private String generateProofreadFix(String combinedPrompt) {
        int target = extractWordCountTarget(combinedPrompt);
        StringBuilder sb = new StringBuilder();
        sb.append("晨曦微露，第一缕金色光芒穿透薄雾。陈轩的呼吸绵长而均匀，体内灵力如涓涓细流般循环不息。\n\n");
        sb.append("他徐徐睁眼，瞳孔中一闪而逝的紫金色光芒转瞬即逝。经过一整夜的苦修，他离那看似不可逾越的屏障又近了一步。\n\n");
        sb.append("「今日，就是决定命运的时刻。」陈轩低声说道。他望向窗外渐亮的天色，心中涌起一股难以言喻的力量。\n\n");
        sb.append("窗外的喧嚣渐起，如潮水般涌来。他整理好衣衫，迈着沉稳的步伐走向演武场。每一步都踏得坚实有力，仿佛在向天地宣告他的决心。\n\n");

        String[] fillers = {
                "风从东方吹来，带着新生的气息。他知道，一切都将从今日改变。天地之间，唯有坚定的意志可以穿越一切障碍。\n\n",
                "演武场上的气氛越发热烈，修士们的灵力波动在空气中交织成一张无形的网。陈轩脚步不停，向着属于他的命运坚定前行。\n\n"
        };

        int idx = 0;
        while (sb.length() < target) {
            sb.append(fillers[idx % fillers.length]);
            idx++;
        }

        return sb.toString();
    }

    private String generateChapterTitle() {
        return "命运初启";
    }

    private String generateCharacterStates() {
        return """
                陈轩：金丹初期，刚突破不久，混沌灵力掌控渐入佳境，准备参加宗门大比
                林雪瑶：金丹中期，冰灵根天才，对陈轩暗生好感，暗中关注其表现
                萧无极：金丹后期，天道院首席弟子，视陈轩为潜在威胁，暗中谋划
                王铁柱：筑基后期，勤修不辍，忠心支持陈轩，在场边为其加油
                李道真：化神期，隐世高人，暗中关注弟子成长，必要时出手相助""";
    }

    private String generateVolumeArc() {
        return "第一卷「初入仙途」围绕主角陈轩获得混沌灵根传承、拜入天道院、参加宗门大比展开。" +
                "本卷的核心冲突是主角作为外门弟子，需要在实力悬殊的环境中证明自己的价值。" +
                "情感线索方面，陈轩与林雪瑶的初遇为后续感情发展埋下伏笔，与萧无极的对立也在本卷初步形成。" +
                "卷末高潮设置为宗门大比决赛，陈轩与萧无极正面交锋，虽败犹荣，赢得众人认可。" +
                "本卷的主题是「证明」——证明出身不能决定命运，证明坚韧可以弥补天赋的差距。" +
                "节奏安排上，前三章为铺垫期，中间五章为发展期，最后两章为高潮期，整体呈渐进式上升。";
    }

    private String generatePlotSummary() {
        return "本章讲述陈轩参加宗门大比，连胜多场后与萧无极展开最终对决，展现出混沌灵根的强大潜力。";
    }

    private String generateCharacterCheck() {
        return "[]";
    }

    private String generateConsistencyCheck() {
        return "[]";
    }

    private String generateContinuityCheck() {
        return "[]";
    }

    private String generateForeshadowing() {
        return "[{\"type\":\"planted\",\"content\":\"李道真暗中传音警告萧无极隐藏底牌\",\"source_chapter\":1}]";
    }

    private String generateGeneric() {
        return "这是一段由Mock AI生成的通用文本内容，用于测试目的。" +
                "文本包含足够的字数以满足基本的内容长度要求。";
    }

    private String getChapterTitleByIndex(int i) {
        String[] titles = {"命运初启", "灵根觉醒", "拜师学艺", "初入宗门", "暗流涌动",
                "演武之争", "突破瓶颈", "危机四伏", "绝地逢生", "实力初显",
                "强敌环伺", "以弱胜强", "剑意初成", "风云际会", "暗夜突袭",
                "绝处逢生", "蜕变之始", "巅峰对决", "真相大白", "新的征程"};
        return titles[(i - 1) % titles.length];
    }
}
