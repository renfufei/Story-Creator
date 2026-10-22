package com.storycreator.core.service;

import com.storycreator.core.domain.ProjectStatus;
import com.storycreator.persistence.entity.ProjectEntity;
import com.storycreator.persistence.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 「已完本 / 已废弃」项目的写入闸门。
 *
 * <p>产品口径（2026-10-21 用户要求）：
 * <ul>
 *   <li>已完本 / 已废弃的项目，<b>不允许再启动新的、会改动正文文本的流程</b>
 *       （各类 AI 生成、扩写、大纲重生成、角色精炼、全局替换、自动创作……）。</li>
 *   <li><b>TTS / 生成 MP3 这类只读加工不拦</b> —— 它们不改文本，只是把已有正文念出来。</li>
 *   <li><b>已经在跑的流程一律放行</b>：本类只在「启动点」被调用一次，
 *       不参与循环、不参与流内部判断。否则跑到一半把项目改成已完本，
 *       正在写的章节会突然失败、状态机卡在 GENERATING。</li>
 * </ul>
 *
 * <p>所以调用规约是：<b>只在新任务开始的那一行之前调用</b>，
 * 不要放进 {@code Flux} 的 doOnNext / 循环体 / 异步任务内部。
 */
@Service
public class ProjectWriteGuard {

    private static final Logger log = LoggerFactory.getLogger(ProjectWriteGuard.class);

    private final ProjectRepository projectRepository;

    public ProjectWriteGuard(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    /**
     * 纯函数：状态 → 拒绝文案。{@code null} 表示可以写。
     *
     * <p>已经拿到 {@link ProjectEntity} 的调用方直接用这个，省一次查询；
     * 也不用把本类注入进那些用 setter 注入、又被 Mockito 打桩的服务里（缺 mock 就 NPE）。
     *
     * @param action 动作名，拼进文案，如 "进行AI生成" / "启动自动创作" / "扩写"
     */
    public static String denyMessage(ProjectStatus status, String action) {
        String reason;
        if (status == ProjectStatus.COMPLETED) {
            reason = "项目状态为「已完本」，正文已定稿";
        } else if (status == ProjectStatus.ABANDONED) {
            reason = "项目状态为「已废弃」，正文已封存";
        } else {
            return null;
        }
        return reason + "，无法" + (action == null || action.isBlank() ? "进行AI生成" : action);
    }

    /** @return {@code true} 表示允许启动新的写流程 */
    public boolean isWritable(Long projectId) {
        if (projectId == null) return true;
        return denyMessage(statusOf(projectId), null) == null;
    }

    /** 允许就静默返回，否则抛 {@link IllegalStateException}（消息可直接给用户看） */
    public void assertWritable(Long projectId, String action) {
        String msg = denyMessage(statusOf(projectId), action);
        if (msg != null) {
            log.info("[P{}] 写入被拦截: {}", projectId, msg);
            throw new IllegalStateException(msg);
        }
    }

    private ProjectStatus statusOf(Long projectId) {
        return projectRepository.findById(projectId)
                .map(ProjectEntity::getStatus)
                .orElse(null);
    }
}
