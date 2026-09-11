package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.TxtImportChapterEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface TxtImportChapterRepository extends JpaRepository<TxtImportChapterEntity, Long> {

    List<TxtImportChapterEntity> findByJobIdOrderByChapterNumber(Long jobId);

    Optional<TxtImportChapterEntity> findByJobIdAndChapterNumber(Long jobId, int chapterNumber);

    /**
     * 删除该任务下的全部已切分章节（重新分割前调用）。
     * 必须用显式 JPQL + 写事务：派生删除会继承 SimpleJpaRepository 的只读事务而不落库，
     * 导致重新插入时触发 (job_id, chapter_number) 唯一键冲突。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from TxtImportChapterEntity c where c.jobId = :jobId")
    void deleteByJobId(@Param("jobId") Long jobId);

    int countByJobId(Long jobId);
}
