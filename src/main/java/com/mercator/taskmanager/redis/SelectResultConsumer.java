package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.FillBatchRegistry;
import com.mercator.taskmanager.service.NicheSelectionService;
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
 * Результат подбора ниши (task_id принадлежит нише) уходит в NicheSelectionService.
 * Результат без stratum_id, не принадлежащий нише (запоздавший ответ на заменённую
 * задачу ниши), пропускается.
 *
 * Транспорт (BRPOP из Redis) — в RedisResultListener; здесь только логика.
 * Привязка результата к страте — по stratum_id из сообщения (эхо парсера).
 */
@Component
public class SelectResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(SelectResultConsumer.class);

    private final StratumRepository stratumRepository;
    private final CardWriteService cardWriteService;
    private final FillBatchRegistry batchRegistry;
    private final CardRepository cardRepository;
    private final NicheSelectionService nicheSelectionService;

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SelectResultConsumer(StratumRepository stratumRepository,
                                CardWriteService cardWriteService,
                                FillBatchRegistry batchRegistry,
                                CardRepository cardRepository,
                                NicheSelectionService nicheSelectionService) {
        this.stratumRepository = stratumRepository;
        this.cardWriteService = cardWriteService;
        this.batchRegistry = batchRegistry;
        this.cardRepository = cardRepository;
        this.nicheSelectionService = nicheSelectionService;
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

        // Результат подбора ниши — отдельная ветка.
        if (nicheSelectionService.isSelectTask(result.getTaskId())) {
            nicheSelectionService.handleResult(result);
            return;
        }

        // Без stratum_id привязать карточки не к чему: запоздавший ответ на задачу
        // подбора ниши, которую уже заменили повторной отправкой.
        if (result.getStratumId() == null) {
            log.warn("Результат подбора без stratum_id пропущен: task_id={} (parser_id={})",
                    result.getTaskId(), result.getParserId());
            return;
        }

        log.info("Результат подбора: task_id={}, stratum_id={}, ok={}, найдено={}, parser_id={}",
                result.getTaskId(), result.getStratumId(),
                result.getOk(), result.getFoundCount(), result.getParserId());

        if (Boolean.FALSE.equals(result.getOk())) {
            log.warn("Подбор task_id={} завершился ошибкой: {}",
                    result.getTaskId(), result.getError());
            return;
        }

        if (result.getCards() == null || result.getCards().isEmpty()) {
            log.info("Подбор task_id={}: карточек нет", result.getTaskId());
            return;
        }

        Optional<StratumEntity> stratumOpt =
                stratumRepository.findById(result.getStratumId());
        if (stratumOpt.isEmpty()) {
            log.error("Страта {} не найдена — результат task_id={} отброшен",
                    result.getStratumId(), result.getTaskId());
            return;
        }
        StratumEntity stratum = stratumOpt.get();

        // Лимит страты: не записывать сверх count. Контроль здесь — единственная
        // общая точка, через которую проходит каждая подобранная карточка
        // (и cron-добор, и ручной запуск). Считаем остаток по актуальному active.
        int active = (int) cardRepository.countActiveStratumMembers(stratum.getId());
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
                log.info("Страта {} ({}): лимит count={} достигнут, "
                                + "оставшиеся {} карточек из результата не пишем",
                        stratum.getId(), stratum.getQuery(), stratum.getCount(),
                        result.getCards().size() - saved);
                break;
            }
            try {
                if (cardWriteService.saveCard(stratum, result.getGeo(), card, result.getParserId())) {
                    saved++;
                }
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