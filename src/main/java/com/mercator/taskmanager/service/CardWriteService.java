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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Запись подобранной/распарсенной карточки в три базы:
 * Postgres (card, stratum_card) + ClickHouse (measurement + card_snapshot).
 *
 * Карточка — товар маркетплейса: один sku — одна карточка (V19). saveCard — товар,
 * подобранный в страту: известный товар получает членство в страте и свежий замер,
 * новый — карточку, членство и первый замер. saveNicheCard / saveNicheCandidate —
 * новая карточка для ниши (проверку, что товара ещё нет, делает вызывающий код).
 *
 * Порядок важен: сначала Postgres (даёт UUID), потом два ClickHouse с этим UUID.
 * Новая карточка сразу получает первый замер, поэтому last_measured_at ставится
 * при создании тем же временем, что parsed_at замера. Иначе обход
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

    /**
     * Товар, подобранный в страту, с гео для замера и меткой парсера.
     * Возвращает false, если товар уже есть в этом сете (один товар в сете — один раз).
     *
     * Без общей транзакции: Postgres (карточка, членство) фиксируется до записи
     * в ClickHouse — при сбое ClickHouse карточка не теряется, замер придёт с обходом.
     * Страта может быть загружена в другой сессии: маркетплейс берётся запросом,
     * id сета — с заглушки (без загрузки), а не через ленивую связь подкатегория → сет.
     */
    public boolean saveCard(StratumEntity stratum, String geo, OzonCard card, String parserId) {
        UUID setId = stratum.getSubcategory().getSet().getId();
        Short marketplaceId = cardRepository.findMarketplaceIdOfStratum(stratum.getId());
        OffsetDateTime measuredAt = OffsetDateTime.now();

        Optional<CardEntity> known = cardRepository.findBySkuAndMarketplace(card.getSku(), marketplaceId);
        if (known.isPresent()) {
            CardEntity existing = known.get();
            if (cardRepository.isInSet(setId, existing.getId())) {
                log.warn("Дубль sku={} в сете {} — товар уже в сете, не записан", card.getSku(), setId);
                return false;
            }
            cardRepository.addToStratum(stratum.getId(), existing.getId(), measuredAt);
            reviveFromSelection(existing, card, measuredAt);
            cardRepository.save(existing);
            writeMeasurementAndSnapshot(existing.getId(), geo, card, parserId, measuredAt);
            log.info("Товар sku={} уже известен (card_id={}) — добавлен в страту {} сета {}",
                    card.getSku(), existing.getId(), stratum.getId(), setId);
            return true;
        }

        CardEntity saved = cardRepository.save(newCardEntity(card, marketplaceId, measuredAt));
        cardRepository.addToStratum(stratum.getId(), saved.getId(), measuredAt);
        writeMeasurementAndSnapshot(saved.getId(), geo, card, parserId, measuredAt);
        return true;
    }

    /** Новая карточка, найденная для ниши (без страты). Возвращает созданную карточку. */
    public CardEntity saveNicheCard(NicheEntity niche, String geo, OzonCard card, String parserId) {
        return saveNicheOwned(niche, geo, card, parserId, "active");
    }

    /**
     * Новый кандидат ниши — карточка со статусом candidate: в обход и аналитику
     * не попадает, пока не отобран в нишу. Первый замер и снимок — данные подбора.
     */
    public CardEntity saveNicheCandidate(NicheEntity niche, String geo, OzonCard card, String parserId) {
        return saveNicheOwned(niche, geo, card, parserId, "candidate");
    }

    private CardEntity saveNicheOwned(NicheEntity niche, String geo, OzonCard card,
                                      String parserId, String status) {
        OffsetDateTime measuredAt = OffsetDateTime.now();
        CardEntity entity = newCardEntity(card, niche.getMarketplace().getId(), measuredAt);
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

    // Известный товар снова подобран в страту: подбор берёт только товары в продаже —
    // карточка активна, счётчики сброшены; паспорт, цена и время замера — из подбора.
    private void reviveFromSelection(CardEntity entity, OzonCard card, OffsetDateTime measuredAt) {
        entity.setStatus("active");
        entity.setFailedAttempts(0);
        entity.setUnavailableSince(null);
        entity.setOutOfStockSince(null);
        entity.setDroppedAt(null);
        entity.setLastMeasuredAt(measuredAt);
        if (card.getName() != null) {
            entity.setName(card.getName());
        }
        if (card.getUrl() != null) {
            entity.setUrl(card.getUrl());
        }
        if (card.getSeller() != null && card.getSeller().getId() != null) {
            entity.setSellerId(card.getSeller().getId());
        }
        if (card.getPrice() != null && card.getPrice().getPrice() != null) {
            entity.setLastPrice(card.getPrice().getPrice());
        }
        if (hasFullContent(card)) {
            entity.setContentTxt(json.toSnakeCaseMap(card));
            entity.setContentTxtParsedAt(measuredAt);
            String tnved = tnvedOrNull(card);
            if (tnved != null) {
                entity.setTnved(tnved);
            }
        }
    }

    // Паспорт новой карточки (Postgres). measuredAt - время первого замера,
    // который пишется сразу вслед за карточкой.
    private CardEntity newCardEntity(OzonCard card, Short marketplaceId, OffsetDateTime measuredAt) {
        CardEntity entity = new CardEntity();
        entity.setMarketplaceId(marketplaceId);
        entity.setSku(card.getSku());
        entity.setName(card.getName());
        entity.setUrl(card.getUrl());
        entity.setSellerId(card.getSeller() != null ? card.getSeller().getId() : null);
        entity.setCollection(resolveCollection(card));
        entity.setStatus("active");
        entity.setFailedAttempts(0);
        entity.setLastMeasuredAt(measuredAt);
        entity.setLastPrice(card.getPrice() != null ? card.getPrice().getPrice() : null);
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