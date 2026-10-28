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
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Страта — один предмет внутри подкатегории (item + поисковый запрос).
 * count — сколько карточек нужно собрать (0 — страта есть в задании, но не подбирается),
 * queryShare — доля предмета в объёме подкатегории, analysisQuota — сколько карточек
 * страты берётся в анализ категории. selectRequestedAt — когда по страте последний раз
 * отправлялась задача подбора (очередь сбора нового сезонного сета).
 *
 * Связь: много страт → одна подкатегория.
 */
@Entity
@Table(name = "stratum")
@Getter
@Setter
public class StratumEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    // EAGER: почти всегда нужен путь до сета (гео и маркетплейс для ключей Redis).
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "subcategory_id", nullable = false)
    private SubcategoryEntity subcategory;

    @Column(nullable = false)
    private String item;

    @Column(nullable = false)
    private String query;

    @Column(nullable = false)
    private Integer count;

    @Column(name = "is_seasonal", nullable = false)
    private Boolean isSeasonal;

    @Column(name = "base_share")
    private BigDecimal baseShare;

    @Column(name = "query_share")
    private BigDecimal queryShare;

    @Column(name = "analysis_quota")
    private Integer analysisQuota;

    @Column(name = "select_requested_at")
    private OffsetDateTime selectRequestedAt;
}