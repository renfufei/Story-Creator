package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TxtImportReStepEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TxtImportReStepRepository extends JpaRepository<TxtImportReStepEntity, Long> {

    List<TxtImportReStepEntity> findByJobIdOrderBySortOrder(Long jobId);

    Optional<TxtImportReStepEntity> findByJobIdAndPhase(Long jobId, String phase);

    /**
     * 把处于执行中的步骤复位为待执行（停止任务 / 进程重启后调用）。
     * 用显式 JPQL + 写事务，避免派生更新继承只读事务而静默失败。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update TxtImportReStepEntity s set s.status = 'PENDING', s.errorMessage = null "
            + "where s.jobId = :jobId and s.status = 'RUNNING'")
    int resetRunningToPending(@Param("jobId") Long jobId);

    /** 进程启动时把上次遗留的「执行中」步骤全部复位为待执行。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update TxtImportReStepEntity s set s.status = 'PENDING', s.errorMessage = null "
            + "where s.status = 'RUNNING'")
    int resetAllRunningToPending();
}
