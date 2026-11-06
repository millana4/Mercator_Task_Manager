package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Журнал обращений к нейросети: когда, зачем, какая модель, сколько токенов.
 * Одна строка — одно обращение (успешное или нет). Тексты не хранятся.
 *
 * purpose: niche_query | prod_specs_card | prod_specs_analysis.
 * ref_type / ref_id — на что ссылается обращение: niche, sku или stratum.
 */
@Entity
@Table(name = "llm_request_log")
@Getter
@Setter
public class LlmRequestLogEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    @Column(nullable = false)
    private Boolean success = false;

    // причина неудачи: вид ошибки и сообщение
    @Column
    private String error;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "ref_type")
    private String refType;

    @Column(name = "ref_id")
    private String refId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}