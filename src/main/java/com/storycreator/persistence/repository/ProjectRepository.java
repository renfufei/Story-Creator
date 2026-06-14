package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.ProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<ProjectEntity, Long> {
    List<ProjectEntity> findAllByOrderByUpdatedAtDesc();
    Optional<ProjectEntity> findByTitle(String title);
}
