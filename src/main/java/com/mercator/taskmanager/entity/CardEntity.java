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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Сущность карточки — товар маркетплейса: один sku — одна карточка, один card_id
 * (уникальность marketplace_id + sku, V19). История замеров — под этим card_id.
 *
 * Состав: страты — таблица stratum_card (товар может входить в страты разных сетов
 * и категорий; закрытое членство — карточка выбыла из страты), ниши — niche_card.
 * niche — ниша, для которой карточка создана (необязательно).
 *
 * status — доступность товара: active | stale | dropped | candidate. candidate —
 * кандидат ниши, в обход и аналитику не попадает. В обходе карточка, если она active
 * и состоит в страте живого сета (active / collecting) или в нише.
 * name — последнее известное имя (снимок). seller_id и collection — для exclude.
 *
 * last_price — цена (price) последнего замера с ценой. boost_base_price и
 * boost_started_at — учащённый обход после резкого скачка цены: цена до скачка
 * и начало учащения; boost_started_at = null — учащения нет.
 *
 * content_txt — карточка от парсера целиком при полном разборе (все
 * характеристики, описание, все переключатели вариантов); null — полного
 * разбора ещё не было. tnved — код ТН ВЭД, когда Ozon его показывает.
 */
@Entity
@Table(name = "card")
@DynamicUpdate
@Getter
@Setter
public class CardEntity {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "marketplace_id", nullable = false)
    private Short marketplaceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "niche_id")
    private NicheEntity niche;

    @Column(nullable = false)
    private String sku;

    @Column
    private String name;

    @Column
    private String url;

    // Главное фото карточки (og:image, вариант 600 px); null — фото ещё не приходило
    @Column(name = "image_url")
    private String imageUrl;

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

    // Полный набор карточки от парсера (полный разбор)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_txt", columnDefinition = "jsonb")
    private Map<String, Object> contentTxt;

    @Column(name = "content_txt_parsed_at")
    private OffsetDateTime contentTxtParsedAt;

    // Код ТН ВЭД (цифры)
    @Column
    private String tnved;

    // Цена (price) последнего замера с ценой — с ней сравнивается новая
    @Column(name = "last_price")
    private Double lastPrice;

    // Учащённый обход после скачка цены: цена до скачка и начало учащения
    @Column(name = "boost_base_price")
    private Double boostBasePrice;

    @Column(name = "boost_started_at")
    private OffsetDateTime boostStartedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}