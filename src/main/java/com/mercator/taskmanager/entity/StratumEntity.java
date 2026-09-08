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
import java.util.UUID;

/**
 * Страта — один предмет внутри подкатегории (item + поисковый запрос).
 * count — сколько карточек нужно собрать, queryShare — доля предмета
 * в объёме подкатегории.
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
}