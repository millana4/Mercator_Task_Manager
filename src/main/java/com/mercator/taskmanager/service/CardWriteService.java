package com.mercator.taskmanager.service;

import com.mercator.taskmanager.clickhouse.CardSnapshot;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.clickhouse.Measurement;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.CardRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Запись одной подобранной/распарсенной карточки в три базы:
 * Postgres (card) + ClickHouse (measurement + card_snapshot).
 *
 * Выделено из SetFillService, чтобы пользоваться и при синхронном
 * наполнении, и при приёме результатов из Redis. Порядок важен:
 * сначала Postgres (даёт UUID), потом два ClickHouse с этим UUID.
 *
 * saveCard — карточка страты; saveNicheCard — карточка, найденная для ниши
 * (без страты). Проверку дубля sku для ниши делает вызывающий код.
 *
 * Новая карточка сразу получает первый замер, поэтому last_measured_at
 * ставится при создании тем же временем, что parsed_at замера. Иначе обход
 * (last_measured_at ASC NULLS FIRST) тут же отправил бы её на повторный парсинг.
 *
 * Полный разбор (full_content=true): карточка от парсера целиком пишется
 * в content_txt, время разбора — в content_txt_parsed_at, код ТН ВЭД — в tnved
 * (только непустой). Новая карточка получает их при создании, существующая —
 * через writeFullContent.
 */
@Service
public class CardWriteService {

    private final CardRepository cardRepository;
    private final MeasurementRepository measurementRepository;
    private final CardSnapshotRepository snapshotRepository;
    private final JsonHelper json;

    private static final Logger log = LoggerFactory.getLogger(CardWriteService.class);

    public CardWriteService(CardRepository cardRepository,
                            MeasurementRepository measurementRepository,
                            CardSnapshotRepository snapshotRepository,
                            JsonHelper json) {
        this.cardRepository = cardRepository;
        this.measurementRepository = measurementRepository;
        this.snapshotRepository = snapshotRepository;
        this.json = json;
    }

    /** Записать карточку под стратой, с гео для замера и меткой парсера. */
    public void saveCard(StratumEntity stratum, String geo, OzonCard card, String parserId) {
        // Инвариант: один sku в сете максимум один раз. Если такой sku уже
        // есть (в этой или другой страте сета) — не пишем дубль.
        UUID setId = stratum.getSubcategory().getSet().getId();
        if (cardRepository.existsByStratumSubcategorySetIdAndSku(setId, card.getSku())) {
            log.warn("Дубль sku={} в сете {} — карточка не записана (уже есть)",
                    card.getSku(), setId);
            return;
        }

        OffsetDateTime measuredAt = OffsetDateTime.now();
        CardEntity entity = newCardEntity(card, measuredAt);
        entity.setStratum(stratum);
        CardEntity savedCard = cardRepository.save(entity);

        writeMeasurementAndSnapshot(savedCard.getId(), geo, card, parserId, measuredAt);
    }

    /** Записать карточку, найденную для ниши (без страты). Возвращает созданную карточку. */
    public CardEntity saveNicheCard(NicheEntity niche, String geo, OzonCard card, String parserId) {
        return saveNicheOwned(niche, geo, card, parserId, "active");
    }

    /**
     * Записать кандидата ниши карточкой со статусом candidate: в обход и аналитику
     * не попадает, пока не отобран в нишу. Первый замер и снимок — данные подбора.
     */
    public CardEntity saveNicheCandidate(NicheEntity niche, String geo, OzonCard card, String parserId) {
        return saveNicheOwned(niche, geo, card, parserId, "candidate");
    }

    private CardEntity saveNicheOwned(NicheEntity niche, String geo, OzonCard card,
                                      String parserId, String status) {
        OffsetDateTime measuredAt = OffsetDateTime.now();
        CardEntity entity = newCardEntity(card, measuredAt);
        entity.setStatus(status);
        entity.setNiche(niche);
        CardEntity savedCard = cardRepository.save(entity);

        writeMeasurementAndSnapshot(savedCard.getId(), geo, card, parserId, measuredAt);
        return savedCard;
    }

    /**
     * Полный набор существующей карточки (обход с полным разбором, проверка кандидата).
     * Без full_content ничего не пишет. Возвращает true, если набор записан.
     */
    public boolean writeFullContent(UUID cardId, OzonCard card, OffsetDateTime parsedAt) {
        if (!hasFullContent(card)) {
            return false;
        }
        cardRepository.updateContent(cardId, json.toSnakeCaseJson(card), parsedAt, tnvedOrNull(card));
        return true;
    }

    // Паспорт карточки (Postgres) без привязки к страте/нише.
    // measuredAt - время первого замера, который пишется сразу вслед за карточкой.
    private CardEntity newCardEntity(OzonCard card, OffsetDateTime measuredAt) {
        CardEntity entity = new CardEntity();
        entity.setSku(card.getSku());
        entity.setName(card.getName());
        entity.setUrl(card.getUrl());
        entity.setSellerId(card.getSeller() != null ? card.getSeller().getId() : null);
        entity.setCollection(resolveCollection(card));
        entity.setStatus("active");
        entity.setFailedAttempts(0);
        entity.setLastMeasuredAt(measuredAt);
        if (hasFullContent(card)) {
            entity.setContentTxt(json.toSnakeCaseMap(card));
            entity.setContentTxtParsedAt(measuredAt);
            entity.setTnved(tnvedOrNull(card));
        }
        return entity;
    }

    // ClickHouse: замер + снимок для уже сохранённой карточки (время - текущее).
    // last_measured_at здесь не трогается: для существующих карточек его ставит вызывающий код.
    public void writeMeasurementAndSnapshot(UUID cardId, String geo, OzonCard card, String parserId) {
        writeMeasurementAndSnapshot(cardId, geo, card, parserId, OffsetDateTime.now());
    }

    // ClickHouse: замер + снимок с заданным временем замера.
    private void writeMeasurementAndSnapshot(UUID cardId, String geo, OzonCard card,
                                             String parserId, OffsetDateTime parsedAt) {
        // 1. Замер.
        Measurement m = Measurement.builder()
                .cardId(cardId)
                .sku(card.getSku())
                .parsedAt(parsedAt)
                .geo(geo)
                .cardPrice(card.getPrice() != null ? card.getPrice().getCardPrice() : null)
                .price(card.getPrice() != null ? card.getPrice().getPrice() : null)
                .originalPrice(card.getPrice() != null ? card.getPrice().getOriginalPrice() : null)
                .quantity(card.getQuantity())
                .rating(parseFloat(card.getRating()))
                .reviewsCount(parseInt(card.getReviewsCount()))
                .parserId(parserId != null ? parserId : "")
                .build();
        measurementRepository.insert(m);

        // 2. Снимок.
        CardSnapshot snap = CardSnapshot.builder()
                .cardId(cardId)
                .sku(card.getSku())
                .parsedAt(parsedAt)
                .name(card.getName())
                .description(card.getDescription())
                .brand(card.getBrand())
                .category(card.getCategory())
                .categoryPath(card.getCategoryPath())
                .characteristics(card.getCharacteristics())
                .variantsAspect(card.getVariantsAspect())
                .variants(json.toJson(card.getVariants()))
                .sellerId(card.getSeller() != null ? card.getSeller().getId() : null)
                .sellerName(card.getSeller() != null ? card.getSeller().getName() : null)
                .sellerLegalName(card.getSeller() != null ? card.getSeller().getLegalName() : null)
                .sellerOgrn(card.getSeller() != null ? card.getSeller().getOgrn() : null)
                .embedding(List.of())
                .build();
        snapshotRepository.insert(snap);
    }

    private static boolean hasFullContent(OzonCard card) {
        return Boolean.TRUE.equals(card.getFullContent());
    }

    private static String tnvedOrNull(OzonCard card) {
        String tnved = card.getTnved();
        return tnved != null && !tnved.isBlank() ? tnved : null;
    }

    private Float parseFloat(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Float.parseFloat(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    private Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return null; }
    }

    // Коллекция карточки — из характеристик по ключу "Коллекция", как есть.
    // Парсер сам разбирает формы ("Весна-лето 2026", "Весна" и т.д.).
    private String resolveCollection(OzonCard card) {
        if (card.getCharacteristics() == null) {
            return null;
        }
        return card.getCharacteristics().get("Коллекция");
    }
}