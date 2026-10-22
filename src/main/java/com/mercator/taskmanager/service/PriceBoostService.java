package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Учащённый обход при резком скачке цены (поле price замера).
 *
 * На каждый замер с ценой:
 *   - учащения нет: |ln(новая / last_price)| > ln(1 + threshold) — скачок:
 *     учащение начинается (boost_base_price = цена до скачка, boost_started_at = время
 *     замера), если на учащении меньше max-cards карточек; иначе скачок пропускается;
 *   - учащение идёт: цена вернулась к цене до скачка (|ln(новая / boost_base_price)|
 *     ≤ ln(1 + return-tolerance)) — учащение снимается (всплеск, вернулась);
 *     иначе, если прошло hold-hours с начала, — снимается (новая реальная цена);
 *   - last_price = новая цена.
 * Замер без цены (нет в наличии) ничего не меняет.
 *
 * Пороги выбраны по замерам 10.07–22.10.2026: скачок от 35% — около 14 в сутки
 * на 650 детских карточек; интенсивность возврата к прежней цене к 48 ч падает
 * до фона (~0,1%/ч). Интервал учащения и паузу при отставании задаёт
 * MonitoringScheduler.
 */
@Service
public class PriceBoostService {

    private static final Logger log = LoggerFactory.getLogger(PriceBoostService.class);

    /** Что произошло с учащением на этом замере. */
    public enum Event { NONE, STARTED, SKIPPED_CAP, RETURNED, ESTABLISHED }

    /** Новое состояние цены и учащения карточки. */
    public record Decision(Double lastPrice, Double boostBasePrice, OffsetDateTime boostStartedAt,
                           Event event) {
    }

    private final CardRepository cardRepository;
    private final double threshold;
    private final double returnTolerance;
    private final long holdHours;
    private final long maxCards;

    public PriceBoostService(CardRepository cardRepository,
                             @Value("${taskmanager.monitoring.boost.threshold}") double threshold,
                             @Value("${taskmanager.monitoring.boost.return-tolerance}") double returnTolerance,
                             @Value("${taskmanager.monitoring.boost.hold-hours}") long holdHours,
                             @Value("${taskmanager.monitoring.boost.max-cards}") long maxCards) {
        this.cardRepository = cardRepository;
        this.threshold = threshold;
        this.returnTolerance = returnTolerance;
        this.holdHours = holdHours;
        this.maxCards = maxCards;
    }

    /**
     * Решение по замеру (без базы). capReached — на учащении уже max-cards карточек.
     */
    public static Decision decide(Double lastPrice, Double boostBasePrice, OffsetDateTime boostStartedAt,
                                  Double price, OffsetDateTime measuredAt, boolean capReached,
                                  double threshold, double returnTolerance, long holdHours) {
        if (price == null || price <= 0) {
            return new Decision(lastPrice, boostBasePrice, boostStartedAt, Event.NONE);
        }
        if (boostStartedAt != null) {
            if (boostBasePrice != null && boostBasePrice > 0
                    && Math.abs(Math.log(price / boostBasePrice)) <= Math.log(1 + returnTolerance)) {
                return new Decision(price, null, null, Event.RETURNED);
            }
            if (!measuredAt.isBefore(boostStartedAt.plusHours(holdHours))) {
                return new Decision(price, null, null, Event.ESTABLISHED);
            }
            return new Decision(price, boostBasePrice, boostStartedAt, Event.NONE);
        }
        if (lastPrice != null && lastPrice > 0
                && Math.abs(Math.log(price / lastPrice)) > Math.log(1 + threshold)) {
            if (capReached) {
                return new Decision(price, null, null, Event.SKIPPED_CAP);
            }
            return new Decision(price, lastPrice, measuredAt, Event.STARTED);
        }
        return new Decision(price, null, null, Event.NONE);
    }

    /** Учесть замер карточки: обновить last_price и состояние учащения. */
    @Transactional
    public void onMeasured(UUID cardId, Double price, OffsetDateTime measuredAt) {
        CardEntity card = cardRepository.findById(cardId).orElse(null);
        if (card == null) {
            return;
        }
        boolean capReached = card.getBoostStartedAt() == null && cardRepository.countBoosted() >= maxCards;
        Decision d = decide(card.getLastPrice(), card.getBoostBasePrice(), card.getBoostStartedAt(),
                price, measuredAt, capReached, threshold, returnTolerance, holdHours);
        if (d.event() == Event.NONE && price == null) {
            return;  // замер без цены — состояние не меняется
        }
        Double before = card.getLastPrice();
        Double base = card.getBoostBasePrice();
        card.setLastPrice(d.lastPrice());
        card.setBoostBasePrice(d.boostBasePrice());
        card.setBoostStartedAt(d.boostStartedAt());
        cardRepository.save(card);

        switch (d.event()) {
            case STARTED -> log.info("Учащение card_id={}: скачок цены {} → {} — обход раз в интервал учащения",
                    cardId, before, price);
            case SKIPPED_CAP -> log.warn("Скачок цены card_id={} ({} → {}) без учащения: на учащении уже {} карточек",
                    cardId, before, price, maxCards);
            case RETURNED -> log.info("Учащение card_id={} снято: цена {} вернулась к цене до скачка {}",
                    cardId, price, base);
            case ESTABLISHED -> log.info("Учащение card_id={} снято: цена {} держится {} ч — новая реальная цена",
                    cardId, price, holdHours);
            default -> { }
        }
    }
}