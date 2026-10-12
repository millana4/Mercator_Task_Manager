package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.matching.CardFeatureExtractor;
import com.mercator.taskmanager.matching.CardFeatures;
import com.mercator.taskmanager.matching.DuplicateFilter;
import com.mercator.taskmanager.matching.NicheMatchingConfig;
import com.mercator.taskmanager.matching.NicheScorer;
import com.mercator.taskmanager.matching.NicheScorer.Candidate;
import com.mercator.taskmanager.matching.NicheScorer.Scored;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.service.NicheRankingStore.ApplyResult;
import com.mercator.taskmanager.service.NicheRankingStore.CandidateView;
import com.mercator.taskmanager.service.NicheRankingStore.Choice;
import com.mercator.taskmanager.service.NicheRankingStore.ScoreUpdate;
import com.mercator.taskmanager.service.NicheRankingStore.VerifyTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ранжирование кандидатов ниши по схожести с референсом (NicheScorer).
 *
 * score — общий шаг для первичного подбора и автодобора: признаки референса
 * (reference_snapshot) и кандидатов (card.content_txt; без полного набора кандидат
 * в этом раунде пропускается), схожесть, запись в кандидатов (similarity — итоговая
 * схожесть, score_details, reject_reason, analog_sku, текст и вектор).
 *
 * rank — первичный подбор после каждой пачки: кандидаты по убыванию схожести
 * без задвоений до plan-count; подставленный размер модели — на проверку.
 * Набрано plan-count — ниша active, иначе selecting: следующая пачка
 * (NicheSelectionService). Решение принимает NicheRankingStore.applySelection.
 *
 * @throws com.mercator.taskmanager.embedding.EmbedderException — эмбеддер
 *         недоступен; планировщик повторит
 */
@Service
public class NicheRankingService {

    private static final Logger log = LoggerFactory.getLogger(NicheRankingService.class);
    static final String NO_REFERENCE = "Нет референса: аналоги подбираются только по карточке-референсу";

    private final NicheRankingStore store;
    private final CardFeatureExtractor extractor;
    private final NicheScorer scorer;
    private final NicheMatchingConfig config;
    private final ParseTaskProducer parseTaskProducer;

    public NicheRankingService(NicheRankingStore store,
                               CardFeatureExtractor extractor,
                               NicheScorer scorer,
                               NicheMatchingConfig config,
                               ParseTaskProducer parseTaskProducer) {
        this.store = store;
        this.extractor = extractor;
        this.scorer = scorer;
        this.config = config;
        this.parseTaskProducer = parseTaskProducer;
    }

    /**
     * Итог расчёта схожести.
     *
     * @param choices   прошедшие фильтр, по убыванию схожести
     * @param embedded  посчитано новых векторов
     * @param noContent пропущено кандидатов без полного набора
     * @param rejected  отсеяно фильтром (другой вид, другой пол)
     */
    public record Ranked(CardFeatures reference, List<Choice> choices, Map<UUID, Double> similarity,
                         int embedded, int noContent, int rejected) {
    }

    /** Схожесть всех кандидатов ниши с референсом; результат записан в кандидатов. */
    public Ranked score(NicheEntity niche) {
        CardFeatures reference = extractor.extract(niche.getReferenceSnapshot());
        List<CandidateView> views = store.loadCandidates(niche.getId());

        List<Candidate> candidates = new ArrayList<>();
        Map<UUID, CandidateView> viewById = new HashMap<>();
        int noContent = 0;
        for (CandidateView v : views) {
            if ("unavailable".equals(v.status())) {
                continue;
            }
            if (v.content() == null) {
                noContent++;
                continue;
            }
            viewById.put(v.id(), v);
            candidates.add(new Candidate(v.id(), extractor.extract(v.content()), v.embedText(), v.embedding()));
        }

        List<Scored> scored = scorer.score(reference, candidates);

        List<ScoreUpdate> updates = new ArrayList<>();
        List<Choice> choices = new ArrayList<>();
        Map<UUID, Double> similarity = new HashMap<>();
        int embedded = 0, rejected = 0;
        for (Scored s : scored) {
            CandidateView v = viewById.get(s.id());
            if (s.vector() != null && (v.embedding() == null || !s.text().equals(v.embedText()))) {
                embedded++;
            }
            String analogSku = s.analogSku().equals(s.features().sku()) ? null : s.analogSku();
            updates.add(new ScoreUpdate(s.id(), s.score(), details(s), s.rejectReason(), analogSku,
                    s.passed() ? s.text() : null, s.passed() ? s.vector() : null));
            if (s.passed()) {
                choices.add(new Choice(s.id(), s.analogSku(), s.features()));
                similarity.put(s.id(), s.score());
            } else {
                rejected++;
            }
        }
        store.saveScores(updates);
        return new Ranked(reference, choices, similarity, embedded, noContent, rejected);
    }

    /** Фильтр задвоений для набора аналогов этой ниши. */
    public DuplicateFilter duplicateFilter(Ranked ranked) {
        return new DuplicateFilter(config, ranked.reference());
    }

    /** Первичный подбор: отбор plan-count самых похожих без задвоений после пачки. */
    public void rank(NicheEntity niche) {
        if (niche.getReferenceSnapshot() == null) {
            store.failNiche(niche.getId(), NO_REFERENCE);
            log.warn("Ниша id={}: нет референса — failed", niche.getId());
            return;
        }
        Ranked ranked = score(niche);

        ApplyResult r = store.applySelection(niche.getId(), ranked.choices(), duplicateFilter(ranked));
        if (r == null) {
            log.info("Ниша id={}: ранжирование не применено — статус уже изменился", niche.getId());
            return;
        }
        sendVerifyTasks(niche, r.toVerify());

        List<Choice> choices = ranked.choices();
        double best = choices.isEmpty() ? 0 : ranked.similarity().get(choices.get(0).candidateId());
        log.info("Ниша id={}: прошли фильтр {} (отсеяно {}, без полного набора {}, новых векторов {}); "
                        + "в нише и на проверке {} из {} (добавлено {}, на проверку {}, выбыло {}, пропущено {}, "
                        + "дублей {}); лучшая схожесть {} — {}",
                niche.getId(), choices.size(), ranked.rejected(), ranked.noContent(), ranked.embedded(),
                r.selected(), niche.getPlanCount(), r.added(), r.reserved(), r.removed(), r.skipped(),
                r.duplicates(), String.format("%.4f", best),
                r.done() ? "подбор завершён (active)" : "следующая пачка");
    }

    /** Отправить проверочный парсинг (с полным разбором) кандидатов и подставленных размеров. */
    public void sendVerifyTasks(NicheEntity niche, List<VerifyTask> tasks) {
        for (VerifyTask t : tasks) {
            ParseTaskMessage task = new ParseTaskMessage();
            task.setTaskId(t.taskId());
            task.setCardId(null);  // проверка кандидата, привязка — по task_id
            task.setSku(t.sku());
            task.setGeo(niche.getGeo().getTitle());
            task.setMarketplace(niche.getMarketplace().getTitle());
            task.setFullContent(true);
            parseTaskProducer.send(task);
        }
    }

    /** Составляющие схожести для score_details; null — кандидат отсеян фильтром. */
    static Map<String, Object> details(Scored s) {
        if (!s.passed()) {
            return null;
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("text", round(s.parts().text()));
        d.put("price", round(s.parts().price()));
        d.put("size", round(s.parts().size()));
        d.put("natural", round(s.parts().natural()));
        d.put("temperature", round(s.parts().temperature()));
        d.put("cosine", round(s.cosine()));
        d.put("analog_sku", s.analogSku());
        d.put("size_label", s.sizeLabel());
        d.put("unit_price", s.analogUnitPrice() == null ? null : Math.round(s.analogUnitPrice() * 100) / 100.0);
        return d;
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10000) / 10000.0;
    }
}