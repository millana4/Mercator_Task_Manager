package com.mercator.taskmanager.service;

import com.mercator.taskmanager.assignment.SetStatus;
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
 *
 * Сезонная замена: черновик не подбирается; сет в сборе (collecting) подбирается
 * только очередью SeasonalFillScheduler (sendCollectingTask), по одной страте;
 * частые SKU для его страты — по страте активного сета с тем же запросом.
 * Добор (refillDeficits) — только по активным сетам. Страты с нулевым
 * количеством не подбираются.
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
     * задач (= число страт с количеством больше нуля). Сами карточки придут
     * позже, в консьюмер. Черновик и сет в сборе так не наполняются.
     */
    public int startFill(UUID setId) {
        SetEntity set = setRepository.findById(setId)
                .orElseThrow(() -> new IllegalArgumentException("Сет не найден: " + setId));
        if (SetStatus.DRAFT.equals(set.getStatus()) || SetStatus.COLLECTING.equals(set.getStatus())) {
            throw new IllegalArgumentException("Сет " + setId + " в статусе " + set.getStatus()
                    + ": черновик не подбирается, сет в сборе подбирается очередью сбора");
        }

        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(setId).stream()
                .filter(st -> st.getCount() > 0)
                .toList();
        if (strata.isEmpty()) {
            throw new IllegalArgumentException("У сета нет страт с количеством больше нуля: " + setId);
        }

        // Открываем партию: ждём столько результатов, сколько страт.
        batchRegistry.open(setId, strata.size(), batchTimeout);

        // Рассылаем задачи по стратам.
        int sent = 0;
        for (StratumEntity stratum : strata) {
            taskProducer.send(buildTask(set, stratum, stratum.getCount(), stratum.getId()));
            sent++;
        }

        log.info("Наполнение сета {} запущено: отправлено {} задач", setId, sent);
        return sent;
    }

    /**
     * Добор недостающих карточек по всем стратам активных сетов.
     * По каждой страте: если active < count — запросить подбор на
     * недостающее, с exclude уже известных (включая dropped, чтобы не
     * подбирать повторно выбывшие sku). Партию НЕ открываем — это фоновой
     * добор, а не первичное наполнение.
     *
     * @return сколько задач добора отправлено.
     */
    public int refillDeficits() {
        List<SetEntity> sets = setRepository.findByStatus(SetStatus.ACTIVE);
        int sent = 0;

        for (SetEntity set : sets) {
            List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(set.getId());
            for (StratumEntity stratum : strata) {
                int active = (int) cardRepository.countActiveStratumMembers(stratum.getId());
                int deficit = stratum.getCount() - active;
                if (deficit <= 0) {
                    continue;
                }

                SelectTaskMessage task = buildTask(set, stratum, deficit, stratum.getId());
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

    /**
     * Задача подбора страты сета в сборе (очередь SeasonalFillScheduler) на count карточек.
     * Частые SKU — по страте активного сета связки с тем же поисковым запросом:
     * у страты нового сета снимков выдачи ещё нет. Такой страты нет — по своей.
     */
    public SelectTaskMessage sendCollectingTask(StratumEntity stratum, int count) {
        SetEntity set = stratum.getSubcategory().getSet();
        UUID frequencyStratumId = stratumRepository.findActiveByQuery(
                        set.getMarketplace().getId(), set.getCategory().getId(),
                        set.getGeo().getId(), stratum.getQuery()).stream()
                .map(StratumEntity::getId)
                .findFirst()
                .orElse(stratum.getId());

        SelectTaskMessage task = buildTask(set, stratum, count, frequencyStratumId);
        taskProducer.send(task);
        log.info("Сбор сета {}: страта {} ({}), задача на {} карточек, частых в выдаче {} (по страте {})",
                set.getId(), stratum.getId(), stratum.getQuery(), count,
                task.getPrioritySkus().size(), frequencyStratumId);
        return task;
    }

    // Задача подбора страты на count карточек; частые SKU выдачи — по frequencyStratumId.
    private SelectTaskMessage buildTask(SetEntity set, StratumEntity stratum, int count,
                                        UUID frequencyStratumId) {
        SelectTaskMessage task = new SelectTaskMessage();
        task.setTaskId(UUID.randomUUID());
        task.setSetId(set.getId());
        task.setStratumId(stratum.getId());
        task.setGeo(set.getGeo().getTitle());
        task.setMarketplace(set.getMarketplace().getTitle());
        task.setCategory(set.getCategory().getTitle());
        task.setQuery(stratum.getQuery());
        task.setCount(count);
        task.setIsSeasonal(stratum.getIsSeasonal());
        task.setBaseShare(stratum.getBaseShare() != null
                ? stratum.getBaseShare().doubleValue() : null);

        // exclude = карточки сета, когда-либо собранные (и выбывшие тоже).
        // При первом наполнении карточек сета нет, при доборе — парсер
        // не повторит собранное и выбывшее.
        List<ExcludedCard> exclude = buildExclude(stratum.getId(), set.getMarketplace().getId());
        task.setExclude(exclude);
        task.setPrioritySkus(buildPrioritySkus(frequencyStratumId, exclude));
        return task;
    }

    // Собрать exclude для страты. Инвариант: товар входит в сет не больше одного раза,
    // поэтому исключаем карточки всех страт сета (не только текущей), когда-либо
    // собранные, и выбывшие тоже. Карточки других сетов и ниш не исключаются: известный
    // товар, подобранный в страту, получает членство без второй карточки (V19).
    // marketplaceId не используется: карточки сета — его маркетплейса.
    private List<ExcludedCard> buildExclude(UUID stratumId, Short marketplaceId) {
        StratumEntity stratum = stratumRepository.findById(stratumId)
                .orElseThrow(() -> new IllegalArgumentException("Страта не найдена: " + stratumId));
        UUID setId = stratum.getSubcategory().getSet().getId();

        List<ExcludedCard> exclude = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        for (StratumEntity s : stratumRepository.findBySubcategorySetId(setId)) {
            for (CardEntity c : cardRepository.findStratumMembers(s.getId())) {
                if (!skus.add(c.getSku())) {
                    continue;
                }
                ExcludedCard ex = new ExcludedCard();
                ex.setSku(c.getSku());
                ex.setName(c.getName());
                ex.setUrl(c.getUrl());
                ex.setSeller(c.getSellerId());
                ex.setCollection(null);
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