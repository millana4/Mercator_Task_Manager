package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Признаки карточки от нейросети (по фото и тексту): фасон, детали, ткань, принт и т. д.,
 * плюс extra — признаки, которых нет в схеме. Одна строка на карточку.
 *
 * input_hash — отпечаток входных данных, prompt_version — версия промпта: если оба
 * совпадают с текущими, карточка не размечается повторно.
 */
@Entity
@Table(name = "prod_specs_card")
@Getter
@Setter
public class ProdSpecsCardEntity {

    @Id
    @Column(name = "card_id", nullable = false, updatable = false)
    private UUID cardId;

    @Column(name = "input_hash", nullable = false)
    private String inputHash;

    @Column(name = "prompt_version", nullable = false)
    private String promptVersion;

    @Column(nullable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attrs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}