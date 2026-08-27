package com.dca.terminal.settings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "app_setting")
public class AppSettingEntity {
    @Id
    @Column(name = "setting_key", nullable = false, length = 64)
    private String key;

    @Column(name = "setting_value", length = 1_000)
    private String value;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public AppSettingEntity() { }

    public AppSettingEntity(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = Instant.now();
    }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public Instant getUpdatedAt() { return updatedAt; }
}
