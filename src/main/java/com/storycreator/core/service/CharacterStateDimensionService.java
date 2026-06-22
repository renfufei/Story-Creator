package com.storycreator.core.service;

import com.storycreator.core.domain.CharacterStateDimension;
import com.storycreator.persistence.entity.CharacterStateDimensionEntity;
import com.storycreator.persistence.repository.CharacterStateDimensionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CharacterStateDimensionService {

    private final CharacterStateDimensionRepository repository;

    public CharacterStateDimensionService(CharacterStateDimensionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<CharacterStateDimensionEntity> ensureAndGet(Long projectId) {
        List<CharacterStateDimensionEntity> existing = repository.findByProjectIdOrderBySortOrder(projectId);
        Set<CharacterStateDimension> existingKeys = existing.stream()
                .map(CharacterStateDimensionEntity::getDimKey)
                .collect(Collectors.toSet());

        List<CharacterStateDimensionEntity> newEntities = new ArrayList<>();
        for (CharacterStateDimension dim : CharacterStateDimension.values()) {
            if (!existingKeys.contains(dim)) {
                CharacterStateDimensionEntity entity = new CharacterStateDimensionEntity(
                        projectId, dim, dim.isDefaultEnabled(), dim.getDefaultSortOrder());
                newEntities.add(entity);
            }
        }

        if (!newEntities.isEmpty()) {
            repository.saveAll(newEntities);
            existing.addAll(newEntities);
            existing.sort((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()));
        }

        return existing;
    }

    public List<String> getEnabledDisplayNames(Long projectId) {
        return ensureAndGet(projectId).stream()
                .filter(CharacterStateDimensionEntity::isEnabled)
                .map(e -> e.getDimKey().getDisplayName())
                .collect(Collectors.toList());
    }

    @Transactional
    public void setEnabled(Long projectId, CharacterStateDimension dim, boolean enabled) {
        CharacterStateDimensionEntity entity = repository.findByProjectIdAndDimKey(projectId, dim)
                .orElseGet(() -> {
                    CharacterStateDimensionEntity e = new CharacterStateDimensionEntity(
                            projectId, dim, dim.isDefaultEnabled(), dim.getDefaultSortOrder());
                    return repository.save(e);
                });
        entity.setEnabled(enabled);
        repository.save(entity);
    }
}
