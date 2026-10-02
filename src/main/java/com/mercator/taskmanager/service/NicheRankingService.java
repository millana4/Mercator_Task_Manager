package com.mercator.taskmanager.service;

import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository.SnapshotText;
import com.mercator.taskmanager.embedding.EmbedderClient;
import com.mercator.taskmanager.embedding.EmbeddingTextBuilder;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.service.NicheRankingStore.ApplyResult;
import com.mercator.taskmanager.service.NicheRankingStore.CandidateView;
import com.mercator.taskmanager.service.NicheRankingStore.Embedded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ранжирование кандидатов ниши.
 *
 * score — общий шаг для первичного подбора и автодобора: тексты новых
 * кандидатов (без эмбеддинга; из выдачи — из данных карточки, из страт —
 * из снимка в ClickHouse; без текста — unavailable), один запрос к эмбеддеру
 * (референс + новые), близость к референсу у всех кандидатов.
 *
 * rank — первичный подбор после каждой пачки: первые plan-count по близости —
 * выборка ниши. Отобрано plan-count — ниша active (подбор завершён), иначе
 * selecting: следующая пачка на недостающее число карточек (NicheSelectionService).
 * Решение принимает NicheRankingStore.applySelection — там известно, сколько
 * кандидатов действительно взято.
 *
 * @throws com.mercator.taskmanager.embedding.EmbedderException — эмбеддер
 *         недоступен; планировщик повторит
 */
@Service
public class NicheRankingService {

    private static final Logger log = LoggerFactory.getLogger(NicheRankingService.class);

    private final NicheRankingStore store;
    private final CardSnapshotRepository snapshotRepository;
    private final EmbedderClient embedder;

    public NicheRankingService(NicheRankingStore store,
                               CardSnapshotRepository snapshotRepository,
                               EmbedderClient embedder) {
        this.store = store;
        this.snapshotRepository = snapshotRepository;
        this.embedder = embedder;
    }

    /** Кандидаты ниши и близость каждого к референсу (без unavailable). */
    public record Scored(List<CandidateView> views, Map<UUID, Double> similarity,
                         int embeddedCount, int unavailableCount) {
    }

    /** Эмбеддинги новых кандидатов и близость всех кандидатов к референсу. */
    public Scored score(NicheEntity niche) {
        List<CandidateView> views = store.loadCandidates(niche.getId());

        // ---------- 1. Тексты новых кандидатов ----------
        List<CandidateView> fresh = views.stream()
                .filter(v -> !v.embedded() && !"unavailable".equals(v.status()))
                .toList();

        List<UUID> stratumCardIds = fresh.stream()
                .filter(v -> v.snapshot() == null && v.cardId() != null)
                .map(CandidateView::cardId)
                .toList();
        Map<UUID, SnapshotText> chTexts = snapshotRepository.findTextsByCardIds(stratumCardIds);

        List<String> texts = new ArrayList<>();
        texts.add(EmbeddingTextBuilder.forReference(niche));  // [0] — референс
        List<CandidateView> toEmbed = new ArrayList<>();
        List<UUID> unavailable = new ArrayList<>();
        for (CandidateView v : fresh) {
            String text = v.snapshot() != null
                    ? EmbeddingTextBuilder.fromSnapshot(v.snapshot())
                    : EmbeddingTextBuilder.fromClickhouse(chTexts.get(v.cardId()));
            if (text.isBlank()) {
                unavailable.add(v.id());
            } else {
                toEmbed.add(v);
                texts.add(text);
            }
        }

        // ---------- 2. Эмбеддинги и близость ----------
        Map<UUID, Double> similarity = new HashMap<>();
        for (CandidateView v : views) {
            if (v.embedded() && v.similarity() != null && !"unavailable".equals(v.status())) {
                similarity.put(v.id(), v.similarity());
            }
        }

        List<Embedded> embedded = new ArrayList<>();
        if (!toEmbed.isEmpty()) {
            List<float[]> vectors = embedder.embed(texts);
            float[] reference = vectors.get(0);
            for (int i = 0; i < toEmbed.size(); i++) {
                float[] vector = vectors.get(i + 1);
                double sim = EmbedderClient.similarity(reference, vector);
                embedded.add(new Embedded(toEmbed.get(i).id(), texts.get(i + 1), vector, sim));
                similarity.put(toEmbed.get(i).id(), sim);
            }
        }
        if (!embedded.isEmpty() || !unavailable.isEmpty()) {
            store.saveEmbeddings(embedded, unavailable);
        }
        return new Scored(views, similarity, embedded.size(), unavailable.size());
    }

    /** id кандидатов по убыванию близости. */
    public static List<UUID> rankedIds(Map<UUID, Double> similarity) {
        return similarity.entrySet().stream()
                .sorted(Map.Entry.<UUID, Double>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .toList();
    }

    /** Первичный подбор: отбор plan-count ближайших после пачки. */
    public void rank(NicheEntity niche) {
        Scored scored = score(niche);
        List<UUID> ranked = rankedIds(scored.similarity());

        ApplyResult r = store.applySelection(niche.getId(), ranked);
        if (r == null) {
            log.info("Ниша id={}: ранжирование не применено — статус уже изменился", niche.getId());
            return;
        }

        Map<UUID, Double> similarity = scored.similarity();
        double best = ranked.isEmpty() ? 0 : similarity.get(ranked.get(0));
        double worstSelected = r.selected() == 0 ? 0
                : similarity.get(ranked.get(Math.min(r.selected(), ranked.size()) - 1));
        log.info("Ниша id={}: ранжировано {} (новых эмбеддингов {}, unavailable {}); в нише {} из {} "
                        + "(добавлено {}, выбыло {}, пропущено {}); близость {}–{} — {}",
                niche.getId(), ranked.size(), scored.embeddedCount(), scored.unavailableCount(),
                r.selected(), niche.getPlanCount(), r.added(), r.removed(), r.skipped(),
                String.format("%.4f", worstSelected), String.format("%.4f", best),
                r.done() ? "подбор завершён (active)" : "следующая пачка");
    }
}