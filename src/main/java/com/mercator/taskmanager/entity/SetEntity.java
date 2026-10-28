package com.mercator.taskmanager.entity;

import com.mercator.taskmanager.assignment.SetStatus;
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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Сущность сета — строка таблицы "set". Общая шапка задания на сбор.
 * Маркетплейс, категория и гео — ссылки на справочники (раньше были текстом).
 *
 * Жизненный цикл (V18): status — см. SetStatus; collecting_at, activated_at, retired_at —
 * когда сет перешёл в сбор, стал активным, выведен. Сезон — season_type + season_year
 * (название — SeasonType.title). Параметры задания (карточек на анализ категории, минимум
 * на подкатегорию, коэффициент ротации) — у сетов, которые ведёт админка; у остальных пусто.
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

    // Без явного статуса сет создаётся активным — как при прежнем импорте.
    @Column(nullable = false)
    private String status = SetStatus.ACTIVE;

    @Column(name = "season_type")
    private String seasonType;

    @Column(name = "season_year")
    private Short seasonYear;

    @Column(name = "analysis_cards")
    private Integer analysisCards;

    @Column(name = "min_subcategory_cards")
    private Integer minSubcategoryCards;

    @Column(name = "rotation_factor", precision = 4, scale = 2)
    private BigDecimal rotationFactor;

    @Column(name = "collecting_at")
    private OffsetDateTime collectingAt;

    @Column(name = "activated_at")
    private OffsetDateTime activatedAt;

    @Column(name = "retired_at")
    private OffsetDateTime retiredAt;
}