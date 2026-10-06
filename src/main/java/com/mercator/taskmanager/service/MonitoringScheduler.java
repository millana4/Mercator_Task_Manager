package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.redis.RedisKeys;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Потоковый обход без барьера. Вместо «залить все active разом и ждать всех»
 * планировщик держит в Redis-очереди небольшой буфер задач и доливает его по
 * мере разбора воркерами.
 *
 * У каждого маркетплейса своя очередь и свой буфер: парсер, настроенный
 * только на одну площадку, всегда имеет что взять, и медленная площадка
 * не тормозит быструю.
 *
 * Каждый тик, по каждому маркетплейсу:
 *   1. смотрит длину очереди parse:tasks:<маркетплейс>:<гео> (LLEN);
 *   2. добирает (buffer − LLEN) карточек-кандидатов этой площадки из Postgres:
 *      самые давно не мерянные (last_measured_at ASC NULLS FIRST), исключая
 *      те, что уже в работе (last_enqueued_at свежее окна ожидания);
 *   3. кладёт задачи в очередь (LPUSH) и помечает last_enqueued_at.
 *
 * Равномерность: карточку обошли → обновился last_measured_at → она уходит
 * в хвост сортировки → вперёд выходят те, кого дольше не трогали.
 * Потерянные задачи сами вернутся в кандидаты, когда last_enqueued_at выйдет
 * за окно ожидания (inflight-timeout).
 */
@Component
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final CardRepository cardRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final ParseTaskProducer parseTaskProducer;
    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    private final int queueBuffer;
    private final long inflightTimeoutMinutes;
    private final String geo;
    private final List<String> slowCategories;   // [""] - медленных категорий нет
    private final long slowMinIntervalHours;

    public MonitoringScheduler(CardRepository cardRepository,
                               MarketplaceRepository marketplaceRepository,
                               ParseTaskProducer parseTaskProducer,
                               StringRedisTemplate redis,
                               RedisKeys keys,
                               @Value("${taskmanager.monitoring.queue-buffer}") int queueBuffer,
                               @Value("${taskmanager.monitoring.inflight-timeout-minutes}") long inflightTimeoutMinutes,
                               @Value("${taskmanager.geo}") String geo,
                               @Value("${taskmanager.monitoring.slow-categories:}") String slowCategories,
                               @Value("${taskmanager.monitoring.slow-min-interval-hours:24}") long slowMinIntervalHours) {
        List<String> slow = Arrays.stream(slowCategories.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        this.slowCategories = slow.isEmpty() ? List.of("") : slow;
        this.slowMinIntervalHours = slowMinIntervalHours;
        log.info("Обход: медленные категории {} - не чаще раза в {} ч",
                slow.isEmpty() ? "нет" : slow, slowMinIntervalHours);
        this.cardRepository = cardRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.parseTaskProducer = parseTaskProducer;
        this.redis = redis;
        this.keys = keys;
        this.queueBuffer = queueBuffer;
        this.inflightTimeoutMinutes = inflightTimeoutMinutes;
        this.geo = geo;
    }

    /**
     * Тик долива. Интервал берётся из конфига (refill-interval-ms).
     * initialDelay даёт приложению и Redis подняться перед первым доливом.
     */
    @Scheduled(fixedDelayString = "${taskmanager.monitoring.refill-interval-ms}", initialDelay = 15_000)
    @Transactional
    public void refill() {
        for (MarketplaceEntity marketplace : marketplaceRepository.findAll()) {
            try {
                refillMarketplace(marketplace);
            } catch (IllegalArgumentException e) {
                // Нет кода для ключа — площадку пропускаем, остальные доливаем.
                log.error("Долив по маркетплейсу '{}' пропущен: {}",
                        marketplace.getTitle(), e.getMessage());
            }
        }
    }

    private void refillMarketplace(MarketplaceEntity marketplace) {
        String title = marketplace.getTitle();
        String queueKey = keys.parseTasks(title, geo);

        Long len = redis.opsForList().size(queueKey);
        int inQueue = (len == null) ? 0 : len.intValue();
        int need = queueBuffer - inQueue;
        if (need <= 0) {
            return;  // очередь уже полна — ничего не доливаем
        }

        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime threshold = now.minusMinutes(inflightTimeoutMinutes);

        List<CardEntity> candidates = cardRepository.findQueueCandidatesByMarketplace(
                marketplace.getId(), threshold,
                slowCategories, now.minusHours(slowMinIntervalHours), need);
        if (candidates.isEmpty()) {
            return;  // нет активных, кого пора обойти
        }

        List<UUID> enqueuedIds = new ArrayList<>(candidates.size());
        for (CardEntity card : candidates) {
            ParseTaskMessage task = new ParseTaskMessage();
            task.setTaskId(UUID.randomUUID());
            task.setCardId(card.getId());
            task.setSku(card.getSku());
            task.setGeo(geo);
            task.setMarketplace(title);

            parseTaskProducer.send(task);
            enqueuedIds.add(card.getId());
        }

        cardRepository.markEnqueued(enqueuedIds, now);

        log.info("Обход: долив очереди {} — было {}, добавлено {} (буфер {})",
                queueKey, inQueue, enqueuedIds.size(), queueBuffer);
    }
}