package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TxtImportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TxtImportJobRepository extends JpaRepository<TxtImportJobEntity, Long> {

    List<TxtImportJobEntity> findByStatusIn(List<String> statuses);

    /** 按项目找最近一次 TXT 导入任务（项目详情页「逆向工程」入口、逆向流程页引导数据都用它定位）。 */
    Optional<TxtImportJobEntity> findFirstByProjectIdOrderByIdDesc(Long projectId);

    /**
     * 应用启动时把上次遗留的「执行中」任务标记为已中断。
     * <p>不再标记为 FAILED —— 逆向工程支持断点续跑，前端会提示「可继续执行剩余部分」。
     * 同时保留历史版本的状态名，便于老数据平滑过渡。
     */
    @Modifying
    @Transactional
    @Query("UPDATE TxtImportJobEntity j SET j.status = 'INTERRUPTED', "
            + "j.progressNote = '应用重启，执行中断，可继续执行剩余部分' "
            + "WHERE j.status IN ('RE_CHAPTER_OUTLINE', 'RE_STORY_ARC', 'RE_WORLD', 'RE_CHARACTERS', "
            + "'RE_STORY_OUTLINE', 'RE_CHARS', 'RE_OUTLINE', 'SPLITTING')")
    int resetStuckStatuses();
}
