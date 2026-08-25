package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Управляет жизненным циклом карточки по итогам обхода и состоянию кнопки.
 *
 * in_cart      -> active, счётчики сброшены.
 * out_of_stock -> карточка жива, но товара нет: замер сохраняется (выше),
 *                 здесь копим out_of_stock_since; если держится дольше
 *                 notify-grace-days -> dropped (+ добор отдельно).
 * not_found    -> сразу dropped (страница удалена).
 * неудача      -> failed_attempts++, после max-failed-attempts -> dropped.
 */
@Service
public class CardLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(CardLifecycleService.class);

    private final CardRepository cardRepository;
    private final int maxFailedAttempts;
    private final int notifyGraceDays;

    public CardLifecycleService(CardRepository cardRepository,
                                @Value("${taskmanager.card.max-failed-attempts:5}") int maxFailedAttempts,
                                @Value("${taskmanager.card.notify-grace-days:7}") int notifyGraceDays) {
        this.cardRepository = cardRepository;
        this.maxFailedAttempts = maxFailedAttempts;
        this.notifyGraceDays = notifyGraceDays;
    }

    /** Товар в продаже (in_cart) — карточка жива, всё сбросить. */
    @Transactional
    public void markInStock(UUID cardId) {
        cardRepository.findById(cardId).ifPresent(card -> {
            card.setStatus("active");
            card.setFailedAttempts(0);
            card.setUnavailableSince(null);
            card.setOutOfStockSince(null);
            card.setDroppedAt(null);
            cardRepository.save(card);
        });
    }

    /**
     * Товара нет в наличии (out_of_stock), но карточка жива.
     * Замер уже сохранён выше. Здесь копим срок отсутствия; если он держится
     * дольше порога — списываем.
     */
    @Transactional
    public void markOutOfStock(UUID cardId) {
        cardRepository.findById(cardId).ifPresent(card -> {
            OffsetDateTime now = OffsetDateTime.now();
            // Сбой парсинга не считаем — это наличие товара, карточка отвечает.
            card.setFailedAttempts(0);
            card.setUnavailableSince(null);
            if (card.getOutOfStockSince() == null) {
                card.setOutOfStockSince(now);           // начало отсутствия
                card.setStatus("active");               // карточка жива
                card.setDroppedAt(null);
                cardRepository.save(card);
                return;
            }
            long days = ChronoUnit.DAYS.between(card.getOutOfStockSince(), now);
            if (days >= notifyGraceDays) {
                card.setStatus("dropped");
                card.setDroppedAt(now);
                log.info("Карточка {} выбыла: нет в наличии {} суток (порог {})",
                        cardId, days, notifyGraceDays);
            } else {
                card.setStatus("active");               // ещё ждём возврата
            }
            cardRepository.save(card);
        });
    }

    /** Страница удалена (not_found, 404) — списываем сразу. */
    @Transactional
    public void markNotFound(UUID cardId) {
        cardRepository.findById(cardId).ifPresent(card -> {
            card.setStatus("dropped");
            card.setDroppedAt(OffsetDateTime.now());
            log.info("Карточка {} выбыла: страница удалена (404)", cardId);
            cardRepository.save(card);
        });
    }

    /** Карточка успешно распарсена — совместимость (эквивалент in_cart). */
    @Transactional
    public void markSuccess(UUID cardId) {
        markInStock(cardId);
    }

    /** Карточка не ответила при обходе (сбой/unknown). */
    @Transactional
    public void markFailure(UUID cardId) {
        cardRepository.findById(cardId).ifPresent(card -> {
            int attempts = card.getFailedAttempts() + 1;
            card.setFailedAttempts(attempts);
            if (card.getUnavailableSince() == null) {
                card.setUnavailableSince(OffsetDateTime.now());
            }
            if (attempts >= maxFailedAttempts) {
                card.setStatus("dropped");
                card.setDroppedAt(OffsetDateTime.now());
                log.info("Карточка {} выбыла после {} неудач", cardId, attempts);
            } else {
                card.setStatus("stale");
            }
            cardRepository.save(card);
        });
    }
}