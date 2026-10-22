package com.mercator.taskmanager.service;

import com.mercator.taskmanager.clickhouse.SearchSnapshotRepository;
import com.mercator.taskmanager.contract.Ozon.ExcludedCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.redis.PrioritySku;
import com.mercator.taskmanager.redis.SelectTaskMessage;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Асинхронное наполнение сета через Redis.
 *
 * По каждой страте формирует задачу подбора и отправляет в список задач
 * (не ждёт ответа). Результаты придут асинхронно в SelectResultConsumer.
 * Открывает партию наполнения, чтобы отслеживать, когда все результаты
 * вернутся (или истечёт таймаут).
 *
 * В задачу кладётся список частых SKU выдачи страты (search_snapshot за
 * search-priority-days суток, по убыванию частоты, без исключённых): парсер
 * берёт их первыми. ClickHouse недоступен — список пустой, подбор как раньше.
 */
@Service
public class SetFillRedisService {

    private static final Logger log = LoggerFactory.getLogger(SetFillRedisService.class);

    private final SetRepository setRepository;
    private final StratumRepository stratumRepository;
    private final SelectTaskProducer taskProducer;
    private final FillBatchRegistry batchRegistry;
    private final CardRepository cardRepository;
    private final SearchSnapshotRepository searchSnapshotRepository;

    // Таймаут партии — настраиваемый, по умолчанию 4 часа.
    private final Duration batchTimeout;

    // Окно частоты SKU в выдаче, суток.
    private final int priorityDays;

    public SetFillRedisService(SetRepository setRepository,
                               StratumRepository stratumRepository,
                               SelectTaskProducer taskProducer,
                               FillBatchRegistry batchRegistry, CardRepository cardRepository,
                               SearchSnapshotRepository searchSnapshotRepository,
                               @Value("${taskmanager.fill.batch-timeout:PT4H}") Duration batchTimeout,
                               @Value("${taskmanager.fill.search-priority-days:7}") int priorityDays) {
        this.setRepository = setRepository;
        this.stratumRepository = stratumRepository;
        this.taskProducer = taskProducer;
        this.batchRegistry = batchRegistry;
        this.cardRepository = cardRepository;
        this.searchSnapshotRepository = searchSnapshotRepository;
        this.batchTimeout = batchTimeout;
        this.priorityDays = priorityDays;
    }

    /**
     * Запустить асинхронное наполнение сета. Возвращает число отправленных
     * задач (= число страт). Сами карточки придут позже, в консьюмер.
     */
    public int startFill(UUID setId) {
        SetEntity set = setRepository.findById(setId)
                .orElseThrow(() -> new IllegalArgumentException("Сет не найден: " + setId));

        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(setId);
        if (strata.isEmpty()) {
            throw new IllegalArgumentException("У сета нет страт: " + setId);
        }

        // Открываем партию: ждём столько результатов, сколько страт.
        batchRegistry.open(setId, strata.size(), batchTimeout);

        // Рассылаем задачи по стратам.
        int sent = 0;
        for (StratumEntity stratum : strata) {
            SelectTaskMessage task = new SelectTaskMessage();
            task.setTaskId(UUID.randomUUID());
            task.setSetId(setId);
            task.setStratumId(stratum.getId());
            task.setGeo(set.getGeo().getTitle());
            task.setMarketplace(set.getMarketplace().getTitle());
            task.setCategory(set.getCategory().getTitle());
            task.setQuery(stratum.getQuery());
            task.setCount(stratum.getCount());
            task.setIsSeasonal(stratum.getIsSeasonal());
            task.setBaseShare(stratum.getBaseShare() != null
                    ? stratum.getBaseShare().doubleValue() : null);

            // exclude = уже собранные карточки сета и карточки ниш маркетплейса.
            // При первом наполнении карточек сета нет, при доборе — парсер
            // не повторит собранное.
            List<ExcludedCard> exclude = buildExclude(stratum.getId(), set.getMarketplace().getId());
            task.setExclude(exclude);
            task.setPrioritySkus(buildPrioritySkus(stratum.getId(), exclude));

            taskProducer.send(task);
            sent++;
        }

        log.info("Наполнение сета {} запущено: отправлено {} задач", setId, sent);
        return sent;
    }

    /**
     * Добор недостающих карточек по всем стратам всех сетов.
     * По каждой страте: если active < count — запросить подбор на
     * недостающее, с exclude уже известных (включая dropped, чтобы не
     * подбирать повторно выбывшие sku). Партию НЕ открываем — это фоновой
     * добор, а не первичное наполнение.
     *
     * @return сколько задач добора отправлено.
     */
    public int refillDeficits() {
        List<SetEntity> sets = setRepository.findAll();
        int sent = 0;

        for (SetEntity set : sets) {
            List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(set.getId());
            for (StratumEntity stratum : strata) {
                int active = cardRepository
                        .findByStratumIdAndStatus(stratum.getId(), "active").size();
                int deficit = stratum.getCount() - active;
                if (deficit <= 0) {
                    continue;
                }

                SelectTaskMessage task = new SelectTaskMessage();
                task.setTaskId(UUID.randomUUID());
                task.setSetId(set.getId());
                task.setStratumId(stratum.getId());
                task.setGeo(set.getGeo().getTitle());
                task.setMarketplace(set.getMarketplace().getTitle());
                task.setCategory(set.getCategory().getTitle());
                task.setQuery(stratum.getQuery());
                task.setCount(deficit);                       // только недостающее
                task.setIsSeasonal(stratum.getIsSeasonal());
                task.setBaseShare(stratum.getBaseShare() != null
                        ? stratum.getBaseShare().doubleValue() : null);
                List<ExcludedCard> exclude = buildExclude(stratum.getId(), set.getMarketplace().getId());
                task.setExclude(exclude);
                task.setPrioritySkus(buildPrioritySkus(stratum.getId(), exclude));

                taskProducer.send(task);
                sent++;

                log.info("Добор страты {} ({}): недостаёт {} (active={}, need={}), частых в выдаче {}",
                        stratum.getId(), stratum.getQuery(), deficit, active, stratum.getCount(),
                        task.getPrioritySkus().size());
            }
        }

        if (sent > 0) {
            log.info("Добор запущен: отправлено {} задач", sent);
        } else {
            log.debug("Добор: дефицитов нет, все страты укомплектованы");
        }
        return sent;
    }

    // Собрать exclude для страты.
    // 1. Инвариант: sku уникален в пределах СЕТА, поэтому исключаем карточки всех
    //    страт сета (не только текущей), чтобы парсер не подобрал sku, уже занятый
    //    в соседней страте.
    // 2. Активные карточки, созданные для ниш на этом маркетплейсе, — только sku
    //    (без продавца, чтобы не блокировать другие товары тех же магазинов),
    //    чтобы категория не завела вторую карточку на тот же товар.
    private List<ExcludedCard> buildExclude(UUID stratumId, Short marketplaceId) {
        StratumEntity stratum = stratumRepository.findById(stratumId)
                .orElseThrow(() -> new IllegalArgumentException("Страта не найдена: " + stratumId));
        UUID setId = stratum.getSubcategory().getSet().getId();

        List<ExcludedCard> exclude = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        for (StratumEntity s : stratumRepository.findBySubcategorySetId(setId)) {
            for (CardEntity c : cardRepository.findByStratumId(s.getId())) {
                ExcludedCard ex = new ExcludedCard();
                ex.setSku(c.getSku());
                ex.setName(c.getName());
                ex.setUrl(c.getUrl());
                ex.setSeller(c.getSellerId());
                ex.setCollection(null);
                exclude.add(ex);
                skus.add(c.getSku());
            }
        }

        for (String sku : cardRepository.findActiveNicheOwnedSkus(marketplaceId)) {
            if (skus.add(sku)) {
                ExcludedCard ex = new ExcludedCard();
                ex.setSku(sku);
                exclude.add(ex);
            }
        }
        return exclude;
    }

    // Частые SKU выдачи страты за окно, по убыванию частоты, без исключённых
    // (парсер их всё равно отбросит). ClickHouse недоступен — пустой список.
    private List<PrioritySku> buildPrioritySkus(UUID stratumId, List<ExcludedCard> exclude) {
        List<PrioritySku> frequent;
        try {
            frequent = searchSnapshotRepository.findSkuFrequency(stratumId, priorityDays);
        } catch (RuntimeException e) {
            log.warn("Частота SKU в выдаче страты {} недоступна, подбор без приоритета: {}",
                    stratumId, e.getMessage());
            return List.of();
        }
        Set<String> excluded = new HashSet<>();
        for (ExcludedCard ex : exclude) {
            excluded.add(ex.getSku());
        }
        List<PrioritySku> result = new ArrayList<>();
        for (PrioritySku p : frequent) {
            if (!excluded.contains(p.sku())) {
                result.add(p);
            }
        }
        return result;
    }
}