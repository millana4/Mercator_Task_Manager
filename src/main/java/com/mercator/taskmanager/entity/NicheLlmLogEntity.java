package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Журнал обращений к нейросети при генерации поискового запроса ниши.
 * Одна строка — одно обращение (успешное или нет).
 */
@Entity
@Table(name = "niche_llm_log")
@Getter
@Setter
public class NicheLlmLogEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "niche_id", nullable = false)
    private NicheEntity niche;

    // модель, ответившая на запрос
    @Column
    private String model;

    @Column(name = "system_prompt", nullable = false)
    private String systemPrompt;

    @Column(name = "user_prompt", nullable = false)
    private String userPrompt;

    // сырой ответ модели
    @Column
    private String response;

    // total_tokens (вход + выход)
    @Column
    private Integer tokens;

    // ответ разобран и прошёл проверку
    @Column(nullable = false)
    private Boolean success = false;

    // причина неудачи
    @Column
    private String error;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}