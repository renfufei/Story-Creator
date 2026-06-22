package com.storycreator.persistence.entity;

import com.storycreator.core.domain.CharacterStateDimension;
import jakarta.persistence.*;

@Entity
@Table(name = "character_state_dimensions", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"project_id", "dim_key"})
})
public class CharacterStateDimensionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_id", nullable = false)
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "dim_key", nullable = false, length = 50)
    private CharacterStateDimension dimKey;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    public CharacterStateDimensionEntity() {}

    public CharacterStateDimensionEntity(Long projectId, CharacterStateDimension dimKey, boolean enabled, int sortOrder) {
        this.projectId = projectId;
        this.dimKey = dimKey;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public CharacterStateDimension getDimKey() { return dimKey; }
    public void setDimKey(CharacterStateDimension dimKey) { this.dimKey = dimKey; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
}
