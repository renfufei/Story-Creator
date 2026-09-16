package com.storycreator.volume;

import com.storycreator.persistence.entity.ChapterEntity;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.entity.VolumeOutlineEntity;
import com.storycreator.persistence.repository.ChapterRepository;
import com.storycreator.persistence.repository.ProjectRepository;
import com.storycreator.persistence.repository.VolumeOutlineRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 分卷 ↔ 章节 绑定服务。
 *
 * <h3>为什么要这一层</h3>
 * 改造前，章节属于哪一卷完全由 {@code projects.chapters_per_volume} 整除推算：
 * <pre>
 *     volumeNumber = (chapterNumber - 1) / chaptersPerVolume + 1
 * </pre>
 * 而这个数字本质是「自动创作时」怎么切卷的默认值。一旦想手工调整（比如「第 2 卷再加 5 章」
 * 或「把这个边缘章节挪到另一卷」），旧模型就无能为力 —— 所以引入了显式的
 * {@code chapters.volume_id}（见 V68）。
 *
 * <h3>兼容策略（关键）</h3>
 * <ol>
 *   <li>{@code projects.volume_binding_enabled = FALSE}（<b>默认值，所有存量项目</b>）：
 *       一切照旧按整除公式推算，行为与改造前完全一致。</li>
 *   <li>{@code = TRUE}（用户在本服务触发过任意一次调整）：以 {@code chapters.volume_id} 为准；
 *       尚未绑定的章节（例如之后新写出来的）仍按原公式兜底，
 *       兜底卷号若不存在则落到最后一卷 —— 保证任何章节都不会「凭空消失」。</li>
 * </ol>
 *
 * <h3>卷实体复用</h3>
 * 沿用已有的 {@code volume_outlines}（本来就是一卷一条：卷号 + 标题 + 弧名 + 弧线），
 * 不另建卷表；{@code chapter_start/chapter_end} 退化为「由绑定推导的冗余展示字段」，
 * 每次变更由 {@link #refreshRanges} 重算，保证按范围过滤的老逻辑也同步生效。
 */
@Service
public class VolumeService {

    /** 自动创作分卷的兜底大小（project.chapters_per_volume 未设置时使用）。 */
    public static final int DEFAULT_CHAPTERS_PER_VOLUME = 10;

    private ChapterRepository chapterRepository;
    private VolumeOutlineRepository volumeOutlineRepository;
    private ProjectRepository projectRepository;

    @Autowired
    public void setChapterRepository(ChapterRepository chapterRepository) {
        this.chapterRepository = chapterRepository;
    }

    @Autowired
    public void setVolumeOutlineRepository(VolumeOutlineRepository volumeOutlineRepository) {
        this.volumeOutlineRepository = volumeOutlineRepository;
    }

    @Autowired
    public void setProjectRepository(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    // ------------------------------------------------------------------
    // 读路径
    // ------------------------------------------------------------------

    /** 每卷章节数的有效值（非法值回落到默认 10）。 */
    public int effectivePerVolume(ProjectEntity project) {
        int n = project.getChaptersPerVolume();
        return n > 0 ? n : DEFAULT_CHAPTERS_PER_VOLUME;
    }

    /** 该项目是否已启用显式绑定。 */
    public boolean isBindingEnabled(Long projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectEntity::isVolumeBindingEnabled)
                .orElse(false);
    }

    /**
     * 解析「每卷 → 实际章节号列表」，是所有读路径（写作/校对分组、阅读目录、透视）的统一入口。
     *
     * <p>卷列表始终来自 {@code volume_outlines}（按卷号升序）；没有卷记录时返回空列表，
     * 与改造前「无卷 → 平铺」的行为一致。</p>
     */
    public List<VolumeChapterGroup> resolveGroups(Long projectId) {
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        if (volumes.isEmpty()) {
            return List.of();
        }
        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        boolean bindingEnabled = projectRepository.findById(projectId)
                .map(ProjectEntity::isVolumeBindingEnabled)
                .orElse(false);

        Map<Integer, List<ChapterEntity>> byNumber = new LinkedHashMap<>();
        for (VolumeOutlineEntity v : volumes) {
            byNumber.put(v.getVolumeNumber(), new ArrayList<>());
        }
        int perVolume = projectRepository.findById(projectId)
                .map(this::effectivePerVolume)
                .orElse(DEFAULT_CHAPTERS_PER_VOLUME);
        Map<Long, Integer> numberByVolumeId = new LinkedHashMap<>();
        for (VolumeOutlineEntity v : volumes) {
            numberByVolumeId.put(v.getId(), v.getVolumeNumber());
        }
        Integer lastVolumeNumber = volumes.isEmpty() ? null : volumes.get(volumes.size() - 1).getVolumeNumber();

        for (ChapterEntity ch : chapters) {
            Integer target = null;
            if (bindingEnabled && ch.getVolumeId() != null) {
                target = numberByVolumeId.get(ch.getVolumeId());
            }
            if (target == null) {
                // 兜底：老公式推算（存量数据 / 之后新增但尚未绑定的章节）
                int computed = (ch.getChapterNumber() - 1) / perVolume + 1;
                if (byNumber.containsKey(computed)) {
                    target = computed;
                } else if (lastVolumeNumber != null) {
                    // 推算卷号已超出实际分卷数（例如新章节超过了最后一卷）：落到最后一卷，避免章节丢失
                    target = lastVolumeNumber;
                }
            }
            if (target != null) {
                byNumber.get(target).add(ch);
            }
        }

        List<VolumeChapterGroup> groups = new ArrayList<>();
        for (VolumeOutlineEntity v : volumes) {
            List<Integer> numbers = byNumber.get(v.getVolumeNumber()).stream()
                    .map(ChapterEntity::getChapterNumber)
                    .sorted()
                    .toList();
            groups.add(new VolumeChapterGroup(v.getId(), v.getVolumeNumber(), v.getTitle(), v.getArcName(), numbers));
        }
        return groups;
    }

    /** 把 {@link #resolveGroups} 的结果转成 Controller 下发用的 Map（带 id + 推导出的章范围）。 */
    public List<Map<String, Object>> resolveGroupMaps(Long projectId) {
        return resolveGroups(projectId).stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.volumeId());
            m.put("volumeNumber", g.volumeNumber());
            m.put("title", g.title() != null ? g.title() : "");
            m.put("arcName", g.arcName() != null ? g.arcName() : "");
            List<Integer> numbers = g.chapterNumbers();
            m.put("chapterNumbers", numbers);
            m.put("chapterStart", numbers.isEmpty() ? 0 : numbers.get(0));
            m.put("chapterEnd", numbers.isEmpty() ? 0 : numbers.get(numbers.size() - 1));
            m.put("chapterCount", numbers.size());
            return m;
        }).toList();
    }

    /**
     * 「第 N 章属于第几卷」的权威映射：绑定优先，未绑定按整除公式兜底，
     * 与 {@link #resolveGroups} 完全同源。
     *
     * <p>用途：给那些只需要按章号查卷号的零散读取点复用，避免各处再各自整除推算、
     * 与用户在分卷管理页做的手工调整脱节。</p>
     */
    public Map<Integer, Integer> volumeNumberByChapter(Long projectId) {
        Map<Integer, Integer> map = new LinkedHashMap<>();
        for (VolumeChapterGroup g : resolveGroups(projectId)) {
            for (int n : g.chapterNumbers()) {
                map.put(n, g.volumeNumber());
            }
        }
        return map;
    }

    /**
     * 单个章节所属卷号。无分卷记录（或该章未落在任何卷内）时返回 empty，
     * 由调用方自行回落到整除公式 —— 保证老项目行为不变。
     */
    public Optional<Integer> volumeNumberOf(Long projectId, int chapterNumber) {
        return Optional.ofNullable(volumeNumberByChapter(projectId).get(chapterNumber));
    }

    // ------------------------------------------------------------------
    // 写路径
    // ------------------------------------------------------------------

    /**
     * 按「每卷 N 章」重建：补齐/调整卷序列，并把<b>所有现有章节</b>写入显式绑定。
     *
     * <p>注意：这里<b>不会</b>改动 {@code projects.chapters_per_volume} —— 那个字段是自动创作时
     * 切卷的默认值；{@code perVolume} 只用于本次重建的计算。</p>
     *
     * @param perVolumeOverride 为空则用项目当前的 chaptersPerVolume
     */
    @Transactional
    public void rebuildBindings(Long projectId, Integer perVolumeOverride) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        int perVolume = (perVolumeOverride != null && perVolumeOverride > 0)
                ? perVolumeOverride
                : effectivePerVolume(project);

        List<ChapterEntity> chapters = chapterRepository.findByProjectIdOrderByChapterNumber(projectId);
        int totalVolumes = chapters.isEmpty() ? 0 : ((chapters.get(chapters.size() - 1).getChapterNumber() - 1) / perVolume + 1);

        List<VolumeOutlineEntity> existing = new ArrayList<>(
                volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId));
        Map<Integer, VolumeOutlineEntity> byNumber = new LinkedHashMap<>();
        for (VolumeOutlineEntity v : existing) {
            byNumber.put(v.getVolumeNumber(), v);
        }

        // 1) 补齐缺失的卷（保留已有卷上的弧线/标题）
        for (int n = 1; n <= totalVolumes; n++) {
            if (byNumber.containsKey(n)) {
                continue;
            }
            VolumeOutlineEntity created = volumeOutlineRepository.save(newVolume(projectId, n, "第" + n + "卷"));
            byNumber.put(n, created);
        }
        // 2) 超出需要的卷：没有任何弧线摘要的直接删掉（多半是历史残留），有内容的保留成空卷，交由用户处置
        for (VolumeOutlineEntity v : existing) {
            if (v.getVolumeNumber() > totalVolumes
                    && (v.getArcSummary() == null || v.getArcSummary().isBlank())) {
                volumeOutlineRepository.delete(v);
                byNumber.remove(v.getVolumeNumber());
            }
        }

        // 3) 写入绑定
        List<VolumeOutlineEntity> fresh = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        if (totalVolumes == 0) {
            for (ChapterEntity ch : chapters) {
                ch.setVolumeId(null);
            }
        } else {
            for (ChapterEntity ch : chapters) {
                int n = (ch.getChapterNumber() - 1) / perVolume + 1;
                VolumeOutlineEntity target = fresh.stream()
                        .filter(v -> v.getVolumeNumber() == n)
                        .findFirst()
                        .orElse(fresh.get(fresh.size() - 1));
                ch.setVolumeId(target.getId());
            }
        }
        chapterRepository.saveAll(chapters);

        // 4) 打开开关 + 刷新冗余范围
        project.setVolumeBindingEnabled(true);
        projectRepository.save(project);
        refreshRanges(projectId);
    }

    /**
     * 把指定章节移动到目标分卷（同时支持「单章移动」与「批量扩卷/缩卷」）。
     *
     * @throws IllegalArgumentException 章节不属于该项目，或目标分卷不存在
     */
    @Transactional
    public void assignChapters(Long projectId, Long volumeId, List<Integer> chapterNumbers) {
        ProjectEntity project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        VolumeOutlineEntity target = requireVolume(projectId, volumeId);

        Map<Integer, ChapterEntity> byNumber = new LinkedHashMap<>();
        for (ChapterEntity ch : chapterRepository.findByProjectIdOrderByChapterNumber(projectId)) {
            byNumber.put(ch.getChapterNumber(), ch);
        }
        List<ChapterEntity> toSave = new ArrayList<>();
        for (int n : chapterNumbers) {
            ChapterEntity ch = byNumber.get(n);
            if (ch == null) {
                throw new IllegalArgumentException("章节不存在（不属于该项目）: 第" + n + "章");
            }
            if (!java.util.Objects.equals(ch.getVolumeId(), target.getId())) {
                ch.setVolumeId(target.getId());
                toSave.add(ch);
            }
        }
        if (!toSave.isEmpty()) {
            chapterRepository.saveAll(toSave);
        }
        project.setVolumeBindingEnabled(true);
        projectRepository.save(project);
        refreshRanges(projectId);
    }

    /** 在末尾新增一个空分卷，返回其 id（后续用 {@link #assignChapters} 把章节挪进去）。 */
    @Transactional
    public Long createVolume(Long projectId, String title) {
        projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        int nextNumber = volumes.isEmpty() ? 1 : volumes.get(volumes.size() - 1).getVolumeNumber() + 1;
        String finalTitle = (title == null || title.isBlank()) ? "第" + nextNumber + "卷" : title.trim();
        VolumeOutlineEntity saved = volumeOutlineRepository.save(newVolume(projectId, nextNumber, finalTitle));

        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setVolumeBindingEnabled(true);
        projectRepository.save(project);
        return saved.getId();
    }

    /**
     * 删除分卷：先把它名下的章节迁到相邻分卷（优先上一卷，无则下一卷），再删除卷记录。
     *
     * @throws IllegalArgumentException 只剩一个分卷时不允许删除
     */
    @Transactional
    public void deleteVolume(Long projectId, Long volumeId) {
        VolumeOutlineEntity target = requireVolume(projectId, volumeId);
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        if (volumes.size() <= 1) {
            throw new IllegalArgumentException("至少要保留一个分卷");
        }
        int idx = -1;
        for (int i = 0; i < volumes.size(); i++) {
            if (volumes.get(i).getId().equals(volumeId)) {
                idx = i;
                break;
            }
        }
        VolumeOutlineEntity fallback = (idx > 0) ? volumes.get(idx - 1) : volumes.get(idx + 1);

        List<ChapterEntity> moved = chapterRepository.findByProjectIdOrderByChapterNumber(projectId).stream()
                .filter(ch -> volumeId.equals(ch.getVolumeId()))
                .toList();
        if (!moved.isEmpty()) {
            for (ChapterEntity ch : moved) {
                ch.setVolumeId(fallback.getId());
            }
            chapterRepository.saveAll(moved);
        }
        // 注意：这里不能调用 rebuildBindings —— 那会按整除公式把所有章节复位，抹掉用户的手工调整。
        // 未显式绑定的章节由 resolveGroups 的动态兜底处理。
        volumeOutlineRepository.delete(target);
        ProjectEntity project = projectRepository.findById(projectId).orElseThrow();
        project.setVolumeBindingEnabled(true);
        projectRepository.save(project);
        refreshRanges(projectId);
    }

    /** 修改分卷标题（空串自动回落到「第N卷」）。 */
    @Transactional
    public void renameVolume(Long projectId, Long volumeId, String title) {
        VolumeOutlineEntity target = requireVolume(projectId, volumeId);
        target.setTitle((title == null || title.isBlank()) ? "第" + target.getVolumeNumber() + "卷" : title.trim());
        volumeOutlineRepository.save(target);
    }

    /** 按当前绑定（含兼容兜底）重算各卷的 chapter_start/chapter_end 冗余字段。 */
    @Transactional
    public void refreshRanges(Long projectId) {
        List<VolumeOutlineEntity> volumes = volumeOutlineRepository.findByProjectIdOrderByVolumeNumber(projectId);
        if (volumes.isEmpty()) {
            return;
        }
        Map<Integer, List<Integer>> numbersByVolumeNumber = new LinkedHashMap<>();
        for (VolumeChapterGroup g : resolveGroups(projectId)) {
            numbersByVolumeNumber.put(g.volumeNumber(), g.chapterNumbers());
        }
        for (VolumeOutlineEntity v : volumes) {
            List<Integer> numbers = numbersByVolumeNumber.get(v.getVolumeNumber());
            if (numbers == null || numbers.isEmpty()) {
                v.setChapterStart(0);
                v.setChapterEnd(0);
            } else {
                v.setChapterStart(numbers.get(0));
                v.setChapterEnd(numbers.get(numbers.size() - 1));
            }
        }
        volumeOutlineRepository.saveAll(volumes);
    }

    // ------------------------------------------------------------------

    private VolumeOutlineEntity requireVolume(Long projectId, Long volumeId) {
        Optional<VolumeOutlineEntity> found = volumeOutlineRepository.findById(volumeId);
        if (found.isEmpty() || !found.get().getProjectId().equals(projectId)) {
            throw new IllegalArgumentException("分卷不存在（不属于该项目）: " + volumeId);
        }
        return found.get();
    }

    private VolumeOutlineEntity newVolume(Long projectId, int volumeNumber, String title) {
        VolumeOutlineEntity v = new VolumeOutlineEntity();
        v.setProjectId(projectId);
        v.setVolumeNumber(volumeNumber);
        v.setTitle(title);
        v.setChapterStart(0);
        v.setChapterEnd(0);
        return v;
    }
}
