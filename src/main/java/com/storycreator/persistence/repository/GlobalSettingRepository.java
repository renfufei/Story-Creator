package com.storycreator.persistence.repository;

import com.storycreator.persistence.entity.GlobalSettingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GlobalSettingRepository extends JpaRepository<GlobalSettingEntity, String> {
}
