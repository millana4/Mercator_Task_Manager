package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Очередь подбора нового сезонного сета (статус collecting). Тик раз в минуту;
 * по маркетплейсу уходит не больше одной задачи за select-interval-minutes (30 мин —
 * 2 задачи в час), считая от последней отправки. Накопления нет: после паузы уходит
 * одна задача, следующая — через интервал.
 *
 * Задача не уходит, пока обход маркетплейса отстаёт (флаг MonitoringScheduler):
 * сначала обход, потом подбор.
 *
 * Какая страта: с дефицитом (active < count), которую ни разу не отправляли или
 * отправляли раньше select-retry-hours (24 ч) назад. Порядок: сначала неотправленные —
 * по убыванию квоты анализа, затем повторы — самые давние первыми. Задача — на дефицит.
 * Время отправки — stratum.select_requested_at (переживает перезапуск).
 */
@Component
public class SeasonalFillScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeasonalFillScheduler.class);

    /** Порядок очереди: неотправленные по убыванию квоты, затем повторы — самые давние. */
    static final Comparator<StratumEntity> QUEUE_ORDER = Comparator
            .comparing((StratumEntity st) -> st.getSelectRequestedAt() != null)
            .thenComparing(StratumEntity::getSelectRequestedAt,
                    Comparator.nullsFirst(Comparator.<OffsetDateTime>naturalOrder()))
            .thenComparing((StratumEntity st) -> st.getAnalysisQuota() == null ? 0 : st.getAnalysisQuota(),
                    Comparator.reverseOrder())
            .thenComparing(StratumEntity::getCount, Comparator.reverseOrder());

    private final MarketplaceRepository marketplaceRepository;
    private final StratumRepository stratumRepository;
    private final CardRepository cardRepository;
    private final SetFillRedisService fillService;
    private final MonitoringScheduler monitoring;
    private final boolean enabled;
    private final Duration interval;
    private final Duration retryAfter;

    public SeasonalFillScheduler(MarketplaceRepository marketplaceRepository,
                                 StratumRepository stratumRepository,
                                 CardRepository cardRepository,
                                 SetFillRedisService fillService,
                                 MonitoringScheduler monitoring,
                                 @Value("${taskmanager.season.enabled:true}") boolean enabled,
                                 @Value("${taskmanager.season.select-interval-minutes:30}") long intervalMinutes,
                                 @Value("${taskmanager.season.select-retry-hours:24}") long retryHours) {
        this.marketplaceRepository = marketplaceRepository;
        this.stratumRepository = stratumRepository;
        this.cardRepository = cardRepository;
        this.fillService = fillService;
        this.monitoring = monitoring;
        this.enabled = enabled;
        this.interval = Duration.ofMinutes(intervalMinutes);
        this.retryAfter = Duration.ofHours(retryHours);
        log.info("Сбор сезонного сета: {}, не чаще раза в {} мин, повтор страты через {} ч",
                enabled ? "включён" : "выключен", intervalMinutes, retryHours);
    }

    // initialDelay больше, чем у обхода (15 с): к первому тику флаг отставания уже посчитан.
    @Scheduled(fixedDelayString = "${taskmanager.season.tick-ms:60000}", initialDelay = 60_000)
    @Transactional
    public void tick() {
        if (!enabled) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        for (MarketplaceEntity marketplace : marketplaceRepository.findAll()) {
            try {
                tickMarketplace(marketplace, now);
            } catch (RuntimeException e) {
                log.error("Сбор сезонного сета по маркетплейсу '{}' пропущен: {}",
                        marketplace.getTitle(), e.getMessage(), e);
            }
        }
    }

    /** Одна задача подбора по маркетплейсу, если можно. Возвращает true, если задача ушла. */
    public boolean tickMarketplace(MarketplaceEntity marketplace, OffsetDateTime now) {
        List<StratumEntity> strata = stratumRepository.findCollectingForSelect(marketplace.getId());
        if (strata.isEmpty()) {
            return false;
        }
        if (monitoring.isLagging(marketplace.getId())) {
            log.debug("Сбор сезонного сета {}: обход отстаёт — подбор ждёт", marketplace.getTitle());
            return false;
        }
        OffsetDateTime last = strata.stream()
                .map(StratumEntity::getSelectRequestedAt)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
        if (last != null && last.isAfter(now.minus(interval))) {
            return false;
        }

        OffsetDateTime retryBefore = now.minus(retryAfter);
        List<StratumEntity> due = strata.stream()
                .filter(st -> st.getSelectRequestedAt() == null || st.getSelectRequestedAt().isBefore(retryBefore))
                .sorted(QUEUE_ORDER)
                .toList();
        for (StratumEntity stratum : due) {
            int active = (int) cardRepository.countActiveStratumMembers(stratum.getId());
            int deficit = stratum.getCount() - active;
            if (deficit <= 0) {
                continue;
            }
            // Сначала задача, потом отметка: Redis недоступен — страта не помечается отправленной.
            fillService.sendCollectingTask(stratum, deficit);
            boolean retry = stratum.getSelectRequestedAt() != null;
            stratum.setSelectRequestedAt(now);
            stratumRepository.save(stratum);
            log.info("Сбор сезонного сета {}: {} страта {} ({}), дефицит {} из {}",
                    marketplace.getTitle(), retry ? "повтор —" : "отправлена", stratum.getId(),
                    stratum.getQuery(), deficit, stratum.getCount());
            return true;
        }
        return false;
    }
}