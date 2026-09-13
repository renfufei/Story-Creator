package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.InspirationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

public interface InspirationRepository extends JpaRepository<InspirationEntity, Long> {

    /** 列表展示顺序：最新创建的排在最前；同一时间戳用 id 兜底保证稳定顺序。 */
    List<InspirationEntity> findByProjectIdOrderByCreatedAtDescIdDesc(Long projectId);

    Optional<InspirationEntity> findByIdAndProjectId(Long id, Long projectId);

    long countByProjectId(Long projectId);

    /**
     * 按项目清理。
     * <p>刻意不用派生删除（{@code deleteByProjectId}）：派生删除继承
     * {@code SimpleJpaRepository} 的类级 {@code readOnly} 事务，删除可能不刷盘，
     * 后续写入会撞约束。项目约定统一用显式 JPQL + {@code @Modifying}。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("delete from InspirationEntity i where i.projectId = :projectId")
    int deleteByProjectId(@Param("projectId") Long projectId);
}
