package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.clickhouse.Measurement;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.service.CardLifecycleService;
import com.mercator.taskmanager.service.NicheReferenceService;
import com.mercator.taskmanager.service.NicheVerificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Обрабатывает результат обхода (пришёл из Redis-списка parse:results:<гео>).
 * На успех: дописывает свежий замер в ClickHouse, карточка active,
 * проставляет last_measured_at (для потокового обхода).
 * На неудачу: помечает карточку через жизненный цикл.
 *
 * Нишевые ветки (по task_id), замер обычным путём не пишется:
 *  - парсинг референса ниши — NicheReferenceService;
 *  - проверочный парсинг кандидата ниши — NicheVerificationService.
 * Результат без card_id, не принадлежащий нишам (запоздавший ответ на
 * заменённую задачу), пропускается.
 *
 * Транспорт (BRPOP из Redis) — в RedisResultListener; здесь только логика.
 */
@Component
public class ParseResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(ParseResultConsumer.class);

    private final MeasurementRepository measurementRepository;
    private final CardLifecycleService lifecycle;
    private final CardRepository cardRepository;
    private final NicheReferenceService nicheReferenceService;
    private final NicheVerificationService nicheVerificationService;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public ParseResultConsumer(MeasurementRepository measurementRepository,
                               CardLifecycleService lifecycle,
                               CardRepository cardRepository,
                               NicheReferenceService nicheReferenceService,
                               NicheVerificationService nicheVerificationService) {
        this.measurementRepository = measurementRepository;
        this.lifecycle = lifecycle;
        this.cardRepository = cardRepository;
        this.nicheReferenceService = nicheReferenceService;
        this.nicheVerificationService = nicheVerificationService;
    }

    /** Обработать одно сообщение результата обхода (JSON). */
    @Transactional
    public void handle(String json) {
        ParseResultMessage result;
        try {
            result = mapper.readValue(json, ParseResultMessage.class);
        } catch (Exception e) {
            log.error("Не разобрать результат обхода: {}", e.getMessage());
            return;
        }

        // Результат парсинга референса ниши — отдельная ветка, замер не пишется.
        if (nicheReferenceService.isReferenceTask(result.getTaskId())) {
            nicheReferenceService.handleResult(result);
            return;
        }

        // Результат проверочного парсинга кандидата ниши (автодобор).
        if (nicheVerificationService.isVerifyTask(result.getTaskId())) {
            nicheVerificationService.handleResult(result);
            return;
        }

        // Без card_id привязать замер не к чему: запоздавший ответ на задачу
        // ниши, которую уже заменили или сняли.
        if (result.getCardId() == null) {
            log.warn("Результат обхода без card_id пропущен: task_id={}, sku={} (parser_id={})",
                    result.getTaskId(), result.getSku(), result.getParserId());
            return;
        }

        // Карточка не ответила — разбираемся, чья это проблема.
        if (Boolean.FALSE.equals(result.getOk()) || result.getCard() == null) {
            // Антибот/капча — сбой сборщика, карточку не штрафуем.
            if ("antibot_blocked".equals(result.getErrorCode())) {
                log.warn("Обход card_id={} пропущен: антибот (parser_id={})",
                        result.getCardId(), result.getParserId());
                return;
            }
            log.warn("Обход card_id={} неуспешен (parser_id={}): {}",
                    result.getCardId(), result.getParserId(), result.getError());
            lifecycle.markFailure(result.getCardId());
            return;
        }

        var card = result.getCard();
        String buttonState = card.getButtonState();

        // Страница удалена (404) — списываем сразу, замер не пишем.
        if ("not_found".equals(buttonState)) {
            log.info("Обход card_id={}: страница удалена (404)", result.getCardId());
            lifecycle.markNotFound(result.getCardId());
            return;
        }

        // Успех: пишем замер (в т.ч. для out_of_stock — факт отсутствия ценен).
        Measurement m = Measurement.builder()
                .cardId(result.getCardId())
                .sku(result.getSku())
                .parsedAt(OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS))
                .geo(result.getGeo())
                .cardPrice(card.getPrice() != null ? card.getPrice().getCardPrice() : null)
                .price(card.getPrice() != null ? card.getPrice().getPrice() : null)
                .originalPrice(card.getPrice() != null ? card.getPrice().getOriginalPrice() : null)
                .quantity(card.getQuantity())
                .rating(parseFloat(card.getRating()))
                .reviewsCount(parseInt(card.getReviewsCount()))
                .parserId(result.getParserId() != null ? result.getParserId() : "")
                .build();
        measurementRepository.insert(m);

        // Потоковый обход: отметить, что карточку только что померили.
        cardRepository.markMeasured(result.getCardId(),
                OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS));

        // Обновляем жизненный цикл по состоянию кнопки.
        if ("out_of_stock".equals(buttonState)) {
            lifecycle.markOutOfStock(result.getCardId());
            log.info("Обход card_id={}: нет в наличии, замер записан (parser_id={})",
                    result.getCardId(), result.getParserId());
        } else {
            lifecycle.markInStock(result.getCardId());
            log.info("Обход card_id={}: замер записан (button_state={}, parser_id={})",
                    result.getCardId(), buttonState, result.getParserId());
        }
    }

    private Float parseFloat(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Float.parseFloat(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }
}