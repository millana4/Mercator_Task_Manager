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
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Подбор кандидатов ниши пачками.
 *
 * Отправка: задача подбора на select-batch-size карточек по запросу нейросети,
 * категория ниши (логика подбора), без сезонного фильтра. exclude — sku всех
 * известных кандидатов ниши и карточек страт-подсказок; только sku: без продавца
 * (иначе заблокируются другие товары тех же магазинов) и без названия (иначе
 * отсекутся чужие товары с таким же типовым названием). Следующая пачка
 * отправляется только после ответа на предыдущую.
 *
 * Результат: новые карточки выдачи — кандидаты search, новые активные карточки
 * страт-подсказок — кандидаты stratum (карточка может входить в несколько ниш).
 * Есть новые — ниша переходит в ranking (ранжирование решит, продолжать ли).
 * Новых нет — выдача исчерпана: ниша active, если в ней есть карточки,
 * ranking — если кандидаты есть, но ещё не ранжированы, failed — если кандидатов нет.
 */
@Service
public class NicheSelectionService {

    private static final Logger log = LoggerFactory.getLogger(NicheSelectionService.class);

    private final NicheRepository nicheRepository;
    private final NicheCandidateRepository candidateRepository;
    private final CardRepository cardRepository;
    private final SelectTaskProducer selectTaskProducer;
    private final int batchSize;
    private final long selectRetryDelayMinutes;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public NicheSelectionService(NicheRepository nicheRepository,
                                 NicheCandidateRepository candidateRepository,
                                 CardRepository cardRepository,
                                 SelectTaskProducer selectTaskProducer,
                                 @Value("${taskmanager.niche.select-batch-size:10}")
                                 int batchSize,
                                 @Value("${taskmanager.niche.select-retry-delay-minutes:30}")
                                 long selectRetryDelayMinutes) {
        this.nicheRepository = nicheRepository;
        this.candidateRepository = candidateRepository;
        this.cardRepository = cardRepository;
        this.selectTaskProducer = selectTaskProducer;
        this.batchSize = batchSize;
        this.selectRetryDelayMinutes = selectRetryDelayMinutes;
    }

    /** Отправить задачу подбора очередной пачки; task_id сохраняется в niche.select_task_id. */
    @Transactional
    public void sendSelectTask(NicheEntity niche) {
        // exclude: все известные кандидаты ниши + карточки страт-подсказок (только sku)
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
        task.setCount(batchSize);
        task.setIsSeasonal(false);
        task.setBaseShare(null);
        task.setExclude(exclude);

        niche.setSelectTaskId(taskId);
        niche.setError(null);
        nicheRepository.save(niche);

        selectTaskProducer.send(task);
        log.info("Ниша id={}: отправлена пачка подбора «{}», count={}, exclude={}, task_id={}",
                niche.getId(), niche.getSearchQuery(), batchSize, exclude.size(), taskId);
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
        if (!NicheStatus.SELECTING.equals(niche.getStatus())) {
            log.info("Ниша id={}: результат подбора пропущен, статус {}",
                    niche.getId(), niche.getStatus());
            return;
        }

        // ---------- 1. Подбор не удался — повтор через паузу ----------
        if (Boolean.FALSE.equals(result.getOk())) {
            niche.setSelectTaskId(null);
            niche.setError("Подбор не удался: " + result.getError());
            log.warn("Ниша id={}: пачка подбора не удалась (parser_id={}), повтор через {} мин: {}",
                    niche.getId(), result.getParserId(), selectRetryDelayMinutes, result.getError());
            return;
        }

        List<NicheCandidateEntity> existing = candidateRepository.findByNicheId(niche.getId());
        Set<String> known = new LinkedHashSet<>();
        for (NicheCandidateEntity c : existing) {
            known.add(c.getSku());
        }

        // ---------- 2. Новые кандидаты из выдачи ----------
        int fromSearch = 0;
        if (result.getCards() != null) {
            for (OzonCard card : result.getCards()) {
                if (card.getSku() == null || !known.add(card.getSku())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> snapshot = mapper.convertValue(card, Map.class);
                snapshot.put("parser_id", result.getParserId());  // метка парсера для первого замера
                NicheCandidateEntity c = new NicheCandidateEntity();
                c.setNiche(niche);
                c.setSku(card.getSku());
                c.setSource("search");
                c.setSnapshot(snapshot);
                c.setStatus("candidate");
                candidateRepository.save(c);
                fromSearch++;
            }
        }

        // ---------- 3. Новые кандидаты из страт-подсказок ----------
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

        // ---------- 4. Новые есть — ранжирование ----------
        if (fromSearch + fromStrata > 0) {
            niche.setStatus(NicheStatus.RANKING);
            niche.setError(null);
            log.info("Ниша id={}: пачка принесла {} новых кандидатов (из выдачи {}, найдено парсером {}; "
                            + "из страт {}), всего кандидатов {}, переход к ранжированию",
                    niche.getId(), fromSearch + fromStrata, fromSearch, result.getFoundCount(),
                    fromStrata, known.size());
            return;
        }

        // ---------- 5. Новых нет — выдача исчерпана ----------
        long active = cardRepository.countActiveInNiche(niche.getId());
        if (active > 0) {
            niche.setStatus(NicheStatus.ACTIVE);
            log.info("Ниша id={}: выдача исчерпана, подбор завершён, карточек в нише {}",
                    niche.getId(), active);
        } else if (!existing.isEmpty()) {
            niche.setStatus(NicheStatus.RANKING);
            log.info("Ниша id={}: выдача исчерпана, кандидаты есть, но ещё не ранжированы",
                    niche.getId());
        } else {
            niche.setStatus(NicheStatus.FAILED);
            niche.setError("Не найдено ни одного кандидата по запросу «" + niche.getSearchQuery() + "»");
            log.warn("Ниша id={}: кандидатов нет — ниша failed", niche.getId());
        }
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