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
 * Сущность карточки — SKU, подобранный парсером под страту и/или нишу.
 * name — последнее известное имя (снимок).
 * seller_id и collection нужны для формирования exclude при доборе.
 * Блок жизненного цикла: status, failedAttempts, отметки времени.
 *
 * Связи: у карточки заполнена страта, ниша или обе
 * (ограничение card_stratum_or_niche_check в базе).
 */
@Entity
@Table(name = "card")
@Getter
@Setter
public class CardEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stratum_id")
    private StratumEntity stratum;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "niche_id")
    private NicheEntity niche;

    @Column(nullable = false)
    private String sku;

    @Column
    private String name;

    @Column
    private String url;

    @Column(name = "seller_id")
    private String sellerId;

    @Column
    private String collection;

    @Column(nullable = false)
    private String status;

    @Column(name = "failed_attempts", nullable = false)
    private Integer failedAttempts;

    @Column(name = "unavailable_since")
    private OffsetDateTime unavailableSince;

    @Column(name = "out_of_stock_since")
    private OffsetDateTime outOfStockSince;

    @Column(name = "dropped_at")
    private OffsetDateTime droppedAt;

    @Column(name = "last_measured_at")
    private OffsetDateTime lastMeasuredAt;

    @Column(name = "last_enqueued_at")
    private OffsetDateTime lastEnqueuedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}