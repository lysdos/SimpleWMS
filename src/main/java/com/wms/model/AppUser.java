package com.wms.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.LocalDateTime;

public class AppUser {
    private Integer id;
    private String username;
    @JsonIgnore   // 永远不要随 JSON 序列化返回(管理接口曾把哈希直接暴露给所有 ADMIN)
    private String passwordHash;
    private String role;          // "ADMIN" | "OPERATOR"
    private Boolean enabled;
    private Boolean mustChangePw;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AppUser() {}

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public Boolean getMustChangePw() { return mustChangePw; }
    public void setMustChangePw(Boolean mustChangePw) { this.mustChangePw = mustChangePw; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}