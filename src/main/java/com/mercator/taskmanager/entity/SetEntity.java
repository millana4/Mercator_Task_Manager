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
 * Сущность сета — строка таблицы "set". Общая шапка задания на сбор.
 * Маркетплейс, категория и гео — ссылки на справочники (раньше были текстом).
 */
@Entity
@Table(name = "set")
@Getter
@Setter
public class SetEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    // Справочники маленькие и нужны почти всегда (ключи Redis), поэтому EAGER.
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "marketplace_id", nullable = false)
    private MarketplaceEntity marketplace;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "geo_id", nullable = false)
    private GeoEntity geo;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}