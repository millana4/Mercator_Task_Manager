package com.mercator.taskmanager.service;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.redis.ParseResultMessage;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.repository.NicheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

/**
 * Парсинг карточки-референса ниши.
 *
 * Отправка: задача обхода по sku референса в ту же очередь, что и обычный
 * обход (parse:tasks:<маркетплейс>:<гео>); card_id = null — нашей карточки
 * у референса нет. task_id задачи сохраняется в niche.reference_task_id.
 *
 * Результат: по task_id ParseResultConsumer передаёт его сюда, замер не пишется.
 * Успех — данные карточки в reference_snapshot, ниша переходит к генерации
 * поискового запроса. Антибот — reference_task_id сбрасывается, планировщик
 * отправит задачу повторно. Референс недоступен — при наличии описания ниша
 * продолжает по описанию, без описания — failed.
 */
@Service
public class NicheReferenceService {

    private static final Logger log = LoggerFactory.getLogger(NicheReferenceService.class);

    private final NicheRepository nicheRepository;
    private final ParseTaskProducer parseTaskProducer;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public NicheReferenceService(NicheRepository nicheRepository,
                                 ParseTaskProducer parseTaskProducer) {
        this.nicheRepository = nicheRepository;
        this.parseTaskProducer = parseTaskProducer;
    }

    /** Отправить задачу парсинга референса; ниша переходит в reference_parsing. */
    @Transactional
    public void sendReferenceTask(NicheEntity niche) {
        UUID taskId = UUID.randomUUID();

        ParseTaskMessage task = new ParseTaskMessage();
        task.setTaskId(taskId);
        task.setCardId(null);  // у референса нет нашей карточки
        task.setSku(niche.getReferenceSku());
        task.setGeo(niche.getGeo().getTitle());
        task.setMarketplace(niche.getMarketplace().getTitle());

        niche.setReferenceTaskId(taskId);
        niche.setStatus(NicheStatus.REFERENCE_PARSING);
        nicheRepository.save(niche);

        parseTaskProducer.send(task);
        log.info("Ниша id={}: отправлен парсинг референса sku={}, task_id={}",
                niche.getId(), niche.getReferenceSku(), taskId);
    }

    /** Принадлежит ли результат обхода задаче референса какой-либо ниши. */
    public boolean isReferenceTask(UUID taskId) {
        return taskId != null && nicheRepository.existsByReferenceTaskId(taskId);
    }

    @Transactional
    public void handleResult(ParseResultMessage result) {
        NicheEntity niche = nicheRepository.findByReferenceTaskId(result.getTaskId()).orElse(null);
        if (niche == null) {
            return;
        }
        if (!NicheStatus.REFERENCE_PARSING.equals(niche.getStatus())) {
            log.info("Ниша id={}: результат референса пропущен, статус {}",
                    niche.getId(), niche.getStatus());
            return;
        }

        // ---------- 1. Антибот — отправить повторно ----------
        if ("antibot_blocked".equals(result.getErrorCode())) {
            niche.setReferenceTaskId(null);  // планировщик отправит задачу заново
            log.warn("Ниша id={}: парсинг референса sku={} заблокирован антиботом (parser_id={}), "
                    + "будет повтор", niche.getId(), niche.getReferenceSku(), result.getParserId());
            return;
        }

        // ---------- 2. Успех — сохранить данные референса ----------
        OzonCard card = result.getCard();
        String failure = failureReason(result, card);
        if (failure == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> snapshot = mapper.convertValue(card, Map.class);
            niche.setReferenceSnapshot(snapshot);
            niche.setStatus(NicheStatus.QUERY_GENERATION);
            log.info("Ниша id={}: референс sku={} получен ({}), переход к генерации запроса",
                    niche.getId(), niche.getReferenceSku(), card.getName());
            return;
        }

        // ---------- 3. Референс недоступен ----------
        if (niche.getDescription() != null) {
            niche.setStatus(NicheStatus.QUERY_GENERATION);
            log.warn("Ниша id={}: референс sku={} недоступен ({}), продолжаем по описанию",
                    niche.getId(), niche.getReferenceSku(), failure);
        } else {
            niche.setStatus(NicheStatus.FAILED);
            niche.setError("Референс недоступен: " + failure);
            log.warn("Ниша id={}: референс sku={} недоступен ({}), описания нет — ниша failed",
                    niche.getId(), niche.getReferenceSku(), failure);
        }
    }

    /** Причина непригодности результата или null, если референс пригоден. */
    private static String failureReason(ParseResultMessage result, OzonCard card) {
        if (Boolean.FALSE.equals(result.getOk()) || card == null) {
            String error = result.getError() != null ? result.getError() : result.getErrorCode();
            return "ошибка парсинга: " + error;
        }
        if ("not_found".equals(card.getButtonState())) {
            return "страница товара удалена";
        }
        if (card.getName() == null || card.getName().isBlank()) {
            return "нет данных карточки (товар закончился или страница пустая)";
        }
        return null;
    }
}