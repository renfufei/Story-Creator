package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TxtImportJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TxtImportJobRepository extends JpaRepository<TxtImportJobEntity, Long> {

    List<TxtImportJobEntity> findByStatusIn(List<String> statuses);

    @Modifying
    @Query("UPDATE TxtImportJobEntity j SET j.status = 'FAILED', j.errorMessage = '应用重启，任务中断' " +
            "WHERE j.status IN ('RE_WORLD', 'RE_CHARS', 'RE_OUTLINE', 'SPLITTING')")
    int resetStuckStatuses();
}
