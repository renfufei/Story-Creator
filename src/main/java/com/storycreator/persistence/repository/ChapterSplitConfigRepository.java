package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.ChapterSplitConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChapterSplitConfigRepository extends JpaRepository<ChapterSplitConfigEntity, Long> {

    List<ChapterSplitConfigEntity> findByEnabledTrueOrderBySortOrder();

    List<ChapterSplitConfigEntity> findAllByOrderBySortOrder();

    List<ChapterSplitConfigEntity> findByBuiltinTrueOrderBySortOrder();

    boolean existsByNameAndBuiltinTrue(String name);
}
