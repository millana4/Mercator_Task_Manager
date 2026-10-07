package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Разовый добор полного набора (content_txt) для активных карточек, у которых
 * его ещё нет. Ставит задачи обхода с пометкой full_content в обычную очередь
 * parse:tasks:<маркетплейс>:<гео> и отмечает карточки last_enqueued_at, чтобы
 * планировщик обхода не поставил их повторно. Результат обрабатывается как
 * обычный обход: замер + content_txt.
 *
 * Карточки, уже стоящие в очереди (last_enqueued_at свежее inflight-timeout),
 * пропускаются — повторный запуск доберёт оставшиеся.
 */
@Service
public class CardContentBackfillService {

    private static final Logger log = LoggerFactory.getLogger(CardContentBackfillService.class);

    private final CardRepository cardRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final ParseTaskProducer parseTaskProducer;
    private final String geo;
    private final long inflightTimeoutMinutes;

    public CardContentBackfillService(CardRepository cardRepository,
                                      MarketplaceRepository marketplaceRepository,
                                      ParseTaskProducer parseTaskProducer,
                                      @Value("${taskmanager.geo}") String geo,
                                      @Value("${taskmanager.monitoring.inflight-timeout-minutes}")
                                      long inflightTimeoutMinutes) {
        this.cardRepository = cardRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.parseTaskProducer = parseTaskProducer;
        this.geo = geo;
        this.inflightTimeoutMinutes = inflightTimeoutMinutes;
    }

    /**
     * Поставить задачи добора. limit — сколько задач всего (null или ≤ 0 — без ограничения).
     * Возвращает число поставленных задач.
     */
    @Transactional
    public int enqueue(Integer limit) {
        int remaining = (limit != null && limit > 0) ? limit : Integer.MAX_VALUE;
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime threshold = now.minusMinutes(inflightTimeoutMinutes);
        int total = 0;

        for (MarketplaceEntity marketplace : marketplaceRepository.findAll()) {
            if (remaining <= 0) {
                break;
            }
            try {
                int sent = enqueueMarketplace(marketplace, threshold, now, remaining);
                total += sent;
                remaining -= sent;
            } catch (IllegalArgumentException e) {
                // Нет кода для ключа очереди — площадку пропускаем, как в обходе.
                log.error("Добор content_txt по маркетплейсу '{}' пропущен: {}",
                        marketplace.getTitle(), e.getMessage());
            }
        }
        log.info("Добор content_txt: поставлено задач {}", total);
        return total;
    }

    private int enqueueMarketplace(MarketplaceEntity marketplace, OffsetDateTime threshold,
                                   OffsetDateTime now, int limit) {
        List<CardEntity> cards = cardRepository.findContentBackfillCandidates(
                marketplace.getId(), threshold, limit);
        if (cards.isEmpty()) {
            return 0;
        }

        List<UUID> ids = new ArrayList<>(cards.size());
        for (CardEntity card : cards) {
            ParseTaskMessage task = new ParseTaskMessage();
            task.setTaskId(UUID.randomUUID());
            task.setCardId(card.getId());
            task.setSku(card.getSku());
            task.setGeo(geo);
            task.setMarketplace(marketplace.getTitle());
            task.setFullContent(true);
            parseTaskProducer.send(task);
            ids.add(card.getId());
        }
        cardRepository.markEnqueued(ids, now);

        log.info("Добор content_txt: маркетплейс {} — поставлено {}", marketplace.getTitle(), ids.size());
        return ids.size();
    }
}