package com.mercator.taskmanager.service;

import com.mercator.taskmanager.contract.Ozon.ExcludedCard;
import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheCandidateEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.redis.SelectResultMessage;
import com.mercator.taskmanager.redis.SelectTaskMessage;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Подбор кандидатов ниши: первичный (пачками) и поиск для автодобора.
 *
 * Отправка: задача подбора по запросу нейросети, категория ниши (логика подбора),
 * без сезонного фильтра. exclude — sku всех известных кандидатов ниши и карточек
 * страт-подсказок; только sku: без продавца (иначе заблокируются другие товары
 * тех же магазинов) и без названия (иначе отсекутся чужие товары с таким же
 * типовым названием).
 *
 * Первичный подбор (ниша selecting): пачка на недостающее до plan-count число
 * карточек, не больше select-batch-size; следующая — только после ответа на
 * предыдущую. Новые кандидаты — search. Есть новые —
 * ниша переходит в ranking. Новых нет — выдача исчерпана: active, если в нише
 * есть карточки, ranking — если кандидаты есть, но не ранжированы, иначе failed.
 *
 * Автодобор (ниша active): задача на дефицит × candidate-multiplier. Новые
 * кандидаты — refill_search; ниша остаётся active, выбор делает NicheRankingStore.
 *
 * Кандидат из выдачи сразу получает карточку: существующую с таким sku на
 * маркетплейсе (её полный набор обновляется данными подбора) или новую со
 * статусом candidate — в обход и аналитику она не попадает, пока не отобрана.
 *
 * Новые активные карточки страт-подсказок в обоих случаях — кандидаты stratum
 * (карточка может входить в несколько ниш).
 */
@Service
public class NicheSelectionService {

    private static final Logger log = LoggerFactory.getLogger(NicheSelectionService.class);

    private final NicheRepository nicheRepository;
    private final NicheCandidateRepository candidateRepository;
    private final CardRepository cardRepository;
    private final CardWriteService cardWriteService;
    private final SelectTaskProducer selectTaskProducer;
    private final int batchSize;
    private final long selectRetryDelayMinutes;

    public NicheSelectionService(NicheRepository nicheRepository,
                                 NicheCandidateRepository candidateRepository,
                                 CardRepository cardRepository,
                                 CardWriteService cardWriteService,
                                 SelectTaskProducer selectTaskProducer,
                                 @Value("${taskmanager.niche.select-batch-size:10}")
                                 int batchSize,
                                 @Value("${taskmanager.niche.select-retry-delay-minutes:30}")
                                 long selectRetryDelayMinutes) {
        this.nicheRepository = nicheRepository;
        this.candidateRepository = candidateRepository;
        this.cardRepository = cardRepository;
        this.cardWriteService = cardWriteService;
        this.selectTaskProducer = selectTaskProducer;
        this.batchSize = batchSize;
        this.selectRetryDelayMinutes = selectRetryDelayMinutes;
    }

    /**
     * Первичный подбор: отправить очередную пачку на недостающее до plan-count
     * число карточек, но не больше select-batch-size.
     */
    @Transactional
    public void sendSelectTask(NicheEntity niche) {
        long selected = candidateRepository.findByNicheId(niche.getId()).stream()
                .filter(c -> "selected".equals(c.getStatus()))
                .count();
        // В статусе selecting отобрано меньше plan-count (иначе ниша уже active),
        // поэтому остаток не меньше 1; ограничение снизу — защита от рассинхронизации.
        int count = (int) Math.max(1, Math.min(batchSize, niche.getPlanCount() - selected));

        UUID taskId = send(niche, count);
        niche.setSelectTaskId(taskId);
        niche.setError(null);
        nicheRepository.save(niche);
        log.info("Ниша id={}: отправлена пачка подбора «{}», count={} (отобрано {} из {}), task_id={}",
                niche.getId(), niche.getSearchQuery(), count, selected, niche.getPlanCount(), taskId);
    }


    /** Автодобор: отправить поиск новых кандидатов на count карточек. */
    @Transactional
    public void sendRefillTask(NicheEntity niche, int count) {
        UUID taskId = send(niche, count);
        niche.setSelectTaskId(taskId);
        niche.setRefillRequestedAt(OffsetDateTime.now());
        nicheRepository.save(niche);
        log.info("Ниша id={}: автодобор — отправлен поиск кандидатов «{}», count={}, task_id={}",
                niche.getId(), niche.getSearchQuery(), count, taskId);
    }

    /** Принадлежит ли результат подбора задаче какой-либо ниши. */
    public boolean isSelectTask(UUID taskId) {
        return taskId != null && nicheRepository.existsBySelectTaskId(taskId);
    }

    @Transactional
    public void handleResult(SelectResultMessage result) {
        NicheEntity niche = nicheRepository.findBySelectTaskId(result.getTaskId()).orElse(null);
        if (niche == null) {
            return;
        }
        if (NicheStatus.SELECTING.equals(niche.getStatus())) {
            handleBatchResult(niche, result);
        } else if (NicheStatus.ACTIVE.equals(niche.getStatus())) {
            handleRefillResult(niche, result);
        } else {
            log.info("Ниша id={}: результат подбора пропущен, статус {}",
                    niche.getId(), niche.getStatus());
        }
    }

    // ---------- Первичный подбор: результат пачки ----------
    private void handleBatchResult(NicheEntity niche, SelectResultMessage result) {
        // Подбор не удался — повтор через паузу
        if (Boolean.FALSE.equals(result.getOk())) {
            niche.setSelectTaskId(null);
            niche.setError("Подбор не удался: " + result.getError());
            log.warn("Ниша id={}: пачка подбора не удалась (parser_id={}), повтор через {} мин: {}",
                    niche.getId(), result.getParserId(), selectRetryDelayMinutes, result.getError());
            return;
        }

        boolean hadCandidates = !candidateRepository.findByNicheId(niche.getId()).isEmpty();
        int[] added = saveNewCandidates(niche, result, "search");
        int fromSearch = added[0];
        int fromStrata = added[1];

        // Новые есть — ранжирование
        if (fromSearch + fromStrata > 0) {
            niche.setStatus(NicheStatus.RANKING);
            niche.setError(null);
            log.info("Ниша id={}: пачка принесла {} новых кандидатов (из выдачи {}, найдено парсером {}; "
                            + "из страт {}), переход к ранжированию",
                    niche.getId(), fromSearch + fromStrata, fromSearch, result.getFoundCount(), fromStrata);
            return;
        }

        // Новых нет — выдача исчерпана
        long active = cardRepository.countActiveInNiche(niche.getId());
        if (active > 0) {
            niche.setStatus(NicheStatus.ACTIVE);
            niche.setSelectTaskId(null);  // поиск не идёт — автодобор сможет стартовать
            log.info("Ниша id={}: выдача исчерпана, подбор завершён, карточек в нише {}",
                    niche.getId(), active);
        } else if (hadCandidates) {
            niche.setStatus(NicheStatus.RANKING);
            log.info("Ниша id={}: выдача исчерпана, кандидаты есть, но ещё не ранжированы",
                    niche.getId());
        } else {
            niche.setStatus(NicheStatus.FAILED);
            niche.setError("Не найдено ни одного кандидата по запросу «" + niche.getSearchQuery() + "»");
            log.warn("Ниша id={}: кандидатов нет — ниша failed", niche.getId());
        }
    }

    // ---------- Автодобор: результат поиска ----------
    private void handleRefillResult(NicheEntity niche, SelectResultMessage result) {
        niche.setSelectTaskId(null);

        if (Boolean.FALSE.equals(result.getOk())) {
            log.warn("Ниша id={}: поиск кандидатов автодобора не удался (parser_id={}), "
                            + "следующий — после интервала: {}",
                    niche.getId(), result.getParserId(), result.getError());
            return;
        }

        int[] added = saveNewCandidates(niche, result, "refill_search");
        log.info("Ниша id={}: автодобор — новых кандидатов {} (из выдачи {}, найдено парсером {}; "
                        + "из страт {})",
                niche.getId(), added[0] + added[1], added[0], result.getFoundCount(), added[1]);
    }

    /**
     * Сохранить новых кандидатов: карточки выдачи с данным source и новые
     * активные карточки страт-подсказок (stratum). Дубли по sku отбрасываются.
     *
     * @return {из выдачи, из страт}
     */
    private int[] saveNewCandidates(NicheEntity niche, SelectResultMessage result, String source) {
        Set<String> known = new LinkedHashSet<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(niche.getId())) {
            known.add(c.getSku());
        }

        int fromSearch = 0;
        if (result.getCards() != null) {
            String geo = niche.getGeo().getTitle();
            String parserId = result.getParserId() != null ? result.getParserId() : "";
            OffsetDateTime parsedAt = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
            for (OzonCard card : result.getCards()) {
                if (card.getSku() == null || !known.add(card.getSku())) {
                    continue;
                }
                NicheCandidateEntity c = new NicheCandidateEntity();
                c.setNiche(niche);
                c.setSku(card.getSku());
                c.setSource(source);
                c.setCard(candidateCard(niche, geo, card, parserId, parsedAt));
                c.setStatus("candidate");
                candidateRepository.save(c);
                fromSearch++;
            }
        }

        int fromStrata = 0;
        for (CardEntity card : hintedActiveCards(niche.getId())) {
            if (!known.add(card.getSku())) {
                continue;
            }
            NicheCandidateEntity c = new NicheCandidateEntity();
            c.setNiche(niche);
            c.setSku(card.getSku());
            c.setSource("stratum");
            c.setCard(card);
            c.setStatus("candidate");
            candidateRepository.save(c);
            fromStrata++;
        }
        return new int[]{fromSearch, fromStrata};
    }

    /**
     * Карточка кандидата из выдачи: существующая с таким sku на маркетплейсе
     * (полный набор обновляется данными подбора) или новая со статусом candidate.
     */
    private CardEntity candidateCard(NicheEntity niche, String geo, OzonCard card,
                                     String parserId, OffsetDateTime parsedAt) {
        Optional<CardEntity> existing =
                cardRepository.findBySkuAndMarketplace(card.getSku(), niche.getMarketplace().getId());
        if (existing.isPresent()) {
            cardWriteService.writeFullContent(existing.get().getId(), card, parsedAt);
            return existing.get();
        }
        return cardWriteService.saveNicheCandidate(niche, geo, card, parserId);
    }

    /** Сформировать и отправить задачу подбора; возвращает task_id. */
    private UUID send(NicheEntity niche, int count) {
        Set<String> excludeSkus = new LinkedHashSet<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(niche.getId())) {
            excludeSkus.add(c.getSku());
        }
        for (CardEntity card : hintedActiveCards(niche.getId())) {
            excludeSkus.add(card.getSku());
        }
        List<ExcludedCard> exclude = new ArrayList<>(excludeSkus.size());
        for (String sku : excludeSkus) {
            ExcludedCard ex = new ExcludedCard();
            ex.setSku(sku);
            exclude.add(ex);
        }

        UUID taskId = UUID.randomUUID();
        SelectTaskMessage task = new SelectTaskMessage();
        task.setTaskId(taskId);
        task.setSetId(null);       // у ниши нет сета
        task.setStratumId(null);   // и страты
        task.setGeo(niche.getGeo().getTitle());
        task.setMarketplace(niche.getMarketplace().getTitle());
        task.setCategory(niche.getCategory().getTitle());
        task.setQuery(niche.getSearchQuery());
        task.setCount(count);
        task.setIsSeasonal(false);
        task.setBaseShare(null);
        task.setExclude(exclude);

        selectTaskProducer.send(task);
        log.info("Ниша id={}: задача подбора task_id={}, exclude={}",
                niche.getId(), taskId, exclude.size());
        return taskId;
    }

    /** Активные карточки страт-подсказок ниши. */
    private List<CardEntity> hintedActiveCards(UUID nicheId) {
        List<CardEntity> cards = new ArrayList<>();
        for (UUID stratumId : nicheRepository.findHintedStratumIds(nicheId)) {
            cards.addAll(cardRepository.findByStratumIdAndStatus(stratumId, "active"));
        }
        return cards;
    }
}