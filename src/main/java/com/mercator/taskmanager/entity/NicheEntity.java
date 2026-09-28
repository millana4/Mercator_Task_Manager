package com.mercator.taskmanager.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Товарная ниша — задание пользователя из сервиса аналитики.
 * Признаки те же, что у сета: маркетплейс (канал Redis), категория
 * (логика подбора), гео. К сету ниша не привязана.
 *
 * hintedStrata — страты, которые предложила нейросеть: среди их карточек
 * ищутся похожие на референс, чтобы не парсить заново.
 */
@Entity
@Table(name = "niche")
@Getter
@Setter
public class NicheEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "marketplace_id", nullable = false)
    private MarketplaceEntity marketplace;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "geo_id", nullable = false)
    private GeoEntity geo;

    // id ниши в сервисе аналитики
    @Column(name = "external_id", nullable = false, unique = true)
    private UUID externalId;

    @Column
    private String description;

    @Column(name = "reference_url")
    private String referenceUrl;

    @Column(name = "reference_sku")
    private String referenceSku;

    // данные карточки-референса от парсера
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "reference_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> referenceSnapshot;

    // поисковый запрос от нейросети
    @Column(name = "search_query")
    private String searchQuery;

    // сколько карточек нужно для анализа
    @Column(name = "target_count", nullable = false)
    private Integer targetCount;

    // сколько держать на парсинге (с запасом)
    @Column(name = "plan_count", nullable = false)
    private Integer planCount;

    @Column(nullable = false)
    private String status;

    // причина статуса failed
    @Column
    private String error;

    @Column(name = "reference_task_id")
    private UUID referenceTaskId;

    // текущая задача подбора (первичный или автодобор)
    @Column(name = "select_task_id")
    private UUID selectTaskId;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "niche_stratum_hint",
            joinColumns = @JoinColumn(name = "niche_id"),
            inverseJoinColumns = @JoinColumn(name = "stratum_id"))
    private Set<StratumEntity> hintedStrata = new HashSet<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}