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
 * Сущность карточки — SKU, подобранный парсером под страту или для ниши.
 * name — последнее известное имя (снимок).
 * seller_id и collection нужны для формирования exclude при доборе.
 * Блок жизненного цикла: status (active | stale | dropped | candidate),
 * failedAttempts, отметки времени. candidate — кандидат ниши, в обход
 * и аналитику не попадает.
 *
 * content_txt — карточка от парсера целиком при полном разборе (все
 * характеристики, описание, все переключатели вариантов); null — полного
 * разбора ещё не было. tnved — код ТН ВЭД, когда Ozon его показывает.
 *
 * Связи: заполнено ровно одно — страта или ниша, для которой карточка создана
 * (ограничения card_stratum_or_niche_check и card_niche_owner_check в базе).
 * Состав ниш — таблица niche_card: карточка может входить в несколько ниш.
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

    // Полный набор карточки от парсера (полный разбор)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_txt", columnDefinition = "jsonb")
    private Map<String, Object> contentTxt;

    @Column(name = "content_txt_parsed_at")
    private OffsetDateTime contentTxtParsedAt;

    // Код ТН ВЭД (цифры)
    @Column
    private String tnved;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;
}