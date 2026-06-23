package com.storycreator.persistence.repository;

import com.storycreator.core.domain.ModelType;
import com.storycreator.persistence.entity.AiModelConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AiModelConfigRepository extends JpaRepository<AiModelConfigEntity, Long> {
    List<AiModelConfigEntity> findByActiveTrue();
    List<AiModelConfigEntity> findByProvider(String provider);
    List<AiModelConfigEntity> findByModelType(ModelType modelType);
    List<AiModelConfigEntity> findByActiveTrueAndModelType(ModelType modelType);
}
