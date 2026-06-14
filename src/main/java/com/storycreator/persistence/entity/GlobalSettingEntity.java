package com.storycreator.persistence.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "global_settings")
public class GlobalSettingEntity {

    @Id
    @Column(name = "setting_key", length = 100)
    private String key;

    @Column(name = "setting_value", length = 500)
    private String value;

    public GlobalSettingEntity() {}

    public GlobalSettingEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
}
