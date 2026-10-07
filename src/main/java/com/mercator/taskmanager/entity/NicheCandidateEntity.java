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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Кандидат подбора ниши: связь «ниша — карточка» с эмбеддингом и близостью
 * к референсу. Карточка кандидата из выдачи создаётся сразу (статус candidate)
 * или берётся существующая; данные кандидата — в card.content_txt и снимке
 * ClickHouse. Все кандидаты хранятся — из них берётся автодобор.
 *
 * source: search | stratum | refill_search
 * status: candidate | selected | unavailable
 */
@Entity
@Table(name = "niche_candidate")
@Getter
@Setter
public class NicheCandidateEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "niche_id", nullable = false)
    private NicheEntity niche;

    @Column(nullable = false)
    private String sku;

    @Column(nullable = false)
    private String source;

    // карточка кандидата
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "card_id")
    private CardEntity card;

    // текст, по которому посчитан эмбеддинг
    @Column(name = "embed_text")
    private String embedText;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(columnDefinition = "real[]")
    private float[] embedding;

    // близость к референсу
    @Column
    private Double similarity;

    @Column(nullable = false)
    private String status = "candidate";

    // задача проверочного парсинга при автодоборе
    @Column(name = "verify_task_id")
    private UUID verifyTaskId;

    // время отправки проверочного парсинга
    @Column(name = "verify_requested_at")
    private OffsetDateTime verifyRequestedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}