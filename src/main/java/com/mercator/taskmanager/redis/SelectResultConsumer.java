package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.entity.SetClothingEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetClothingRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.FillBatchRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * Обрабатывает результат подбора (пришёл из Redis-списка select:results:<гео>).
 * Раскладывает карточки через CardWriteService, отмечает партию наполнения.
 *
 * Транспорт (BRPOP из Redis) — в RedisResultListener; здесь только логика.
 * Привязка результата к страте — по stratum_id из сообщения (эхо парсера).
 */
@Component
public class SelectResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(SelectResultConsumer.class);

    private final SetClothingRepository stratumRepository;
    private final CardWriteService cardWriteService;
    private final FillBatchRegistry batchRegistry;
    private final CardRepository cardRepository;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SelectResultConsumer(SetClothingRepository stratumRepository,
                                CardWriteService cardWriteService,
                                FillBatchRegistry batchRegistry,
                                CardRepository cardRepository) {
        this.stratumRepository = stratumRepository;
        this.cardWriteService = cardWriteService;
        this.batchRegistry = batchRegistry;
        this.cardRepository = cardRepository;
    }

    /** Обработать одно сообщение результата подбора (JSON). */
    public void handle(String json) {
        SelectResultMessage result;
        try {
            result = mapper.readValue(json, SelectResultMessage.class);
        } catch (Exception e) {
            log.error("Не разобрать результат подбора, сообщение пропущено: {}", e.getMessage());
            return;  // битое сообщение пропускаем, не застреваем
        }

        log.info("Результат подбора: task_id={}, stratum_id={}, ok={}, найдено={}",
                result.getTaskId(), result.getStratumId(),
                result.getOk(), result.getFoundCount());

        if (Boolean.FALSE.equals(result.getOk())) {
            log.warn("Подбор task_id={} завершился ошибкой: {}",
                    result.getTaskId(), result.getError());
            return;
        }

        if (result.getCards() == null || result.getCards().isEmpty()) {
            log.info("Подбор task_id={}: карточек нет", result.getTaskId());
            return;
        }

        Optional<SetClothingEntity> stratumOpt =
                stratumRepository.findById(result.getStratumId());
        if (stratumOpt.isEmpty()) {
            log.error("Страта {} не найдена — результат task_id={} отброшен",
                    result.getStratumId(), result.getTaskId());
            return;
        }
        SetClothingEntity stratum = stratumOpt.get();

        // Лимит страты: не записывать сверх count. Контроль здесь — единственная
        // общая точка, через которую проходит каждая подобранная карточка
        // (и cron-добор, и ручной запуск). Считаем остаток по актуальному active.
        int active = cardRepository.countByStratumIdAndStatus(stratum.getId(), "active");
        int remaining = stratum.getCount() - active;
        if (remaining <= 0) {
            log.warn("Страта {} ({}) уже укомплектована (active={}, count={}) — "
                            + "результат task_id={} с {} карточками отброшен целиком",
                    stratum.getId(), stratum.getQuery(), active, stratum.getCount(),
                    result.getTaskId(), result.getCards().size());
            return;
        }

        int saved = 0;
        for (var card : result.getCards()) {
            if (saved >= remaining) {
                log.info("Страта {} ({}): лимиt count={} достигнут, "
                                + "оставшиеся {} карточек из результата не пишем",
                        stratum.getId(), stratum.getQuery(), stratum.getCount(),
                        result.getCards().size() - saved);
                break;
            }
            try {
                cardWriteService.saveCard(stratum, result.getGeo(), card);
                saved++;
            } catch (Exception e) {
                log.error("Карточка sku={} не записана (task_id={}): {}",
                        card.getSku(), result.getTaskId(), e.getMessage(), e);
            }
        }
        log.info("Результат task_id={}: записано {} карточек (остаток был {})",
                result.getTaskId(), saved, remaining);
        batchRegistry.recordResult(result.getSetId(), saved);
    }
}