package com.mercator.taskmanager.service;

import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Чистка очереди подбора: задачи страт старше срока жизни (select-queue.ttl, 6 ч) убираются,
 * чтобы они не копились, пока парсеры заняты обходом. Для страты сета в сборе сбрасывается
 * select_requested_at: планировщик сбора отправит её заново на ближайшем тике, а не через
 * select-retry-hours. Задачи ниш не трогаются. Задачи без created_at (прежняя версия)
 * считаются просроченными.
 */
@Component
public class SelectQueueSweeper {

    private static final Logger log = LoggerFactory.getLogger(SelectQueueSweeper.class);

    private final SetRepository setRepository;
    private final StratumRepository stratumRepository;
    private final SelectTaskProducer producer;
    private final boolean enabled;
    private final Duration ttl;

    public SelectQueueSweeper(SetRepository setRepository,
                              StratumRepository stratumRepository,
                              SelectTaskProducer producer,
                              @Value("${taskmanager.select-queue.enabled:true}") boolean enabled,
                              @Value("${taskmanager.select-queue.ttl:PT6H}") Duration ttl) {
        this.setRepository = setRepository;
        this.stratumRepository = stratumRepository;
        this.producer = producer;
        this.enabled = enabled;
        this.ttl = ttl;
        log.info("Очередь подбора: срок жизни задачи {}, чистка {}", ttl, enabled ? "включена" : "выключена");
    }

    @Scheduled(fixedDelayString = "${taskmanager.select-queue.sweep-ms:300000}", initialDelay = 120_000)
    @Transactional
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            sweep(OffsetDateTime.now());
        } catch (RuntimeException e) {
            log.error("Чистка очереди подбора пропущена: {}", e.getMessage(), e);
        }
    }

    /** Убрать просроченные задачи страт. Возвращает, сколько убрано. */
    public int sweep(OffsetDateTime now) {
        Set<String> queues = new LinkedHashSet<>();
        for (String status : List.of(SetStatus.ACTIVE, SetStatus.COLLECTING)) {
            for (SetEntity set : setRepository.findByStatus(status)) {
                queues.add(producer.queueKey(set.getMarketplace().getTitle(), set.getGeo().getTitle()));
            }
        }
        int removed = 0;
        for (String key : queues) {
            List<UUID> stale = producer.removeStale(key, ttl, now);
            if (stale.isEmpty()) {
                continue;
            }
            int released = 0;
            for (UUID stratumId : stale) {
                if (release(stratumId)) {
                    released++;
                }
            }
            removed += stale.size();
            log.info("Очередь подбора {}: убрано {} просроченных задач (срок {}), "
                    + "страт сета в сборе к повторной отправке {}", key, stale.size(), ttl, released);
        }
        return removed;
    }

    // Страта сета в сборе снова считается неотправленной - планировщик пошлёт её заново.
    private boolean release(UUID stratumId) {
        Optional<StratumEntity> found = stratumRepository.findById(stratumId);
        if (found.isEmpty()) {
            return false;
        }
        StratumEntity stratum = found.get();
        if (!SetStatus.COLLECTING.equals(stratum.getSubcategory().getSet().getStatus())) {
            return false;
        }
        stratum.setSelectRequestedAt(null);
        stratumRepository.save(stratum);
        return true;
    }
}