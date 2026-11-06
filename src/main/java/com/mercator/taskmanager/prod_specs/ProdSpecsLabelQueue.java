package com.mercator.taskmanager.prod_specs;

import com.mercator.taskmanager.llm.LlmException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фоновая очередь разметки карточек: пачка обрабатывается одним потоком, между запросами
 * к модели пауза. Карточка, которая уже стоит в очереди или в работе, повторно не ставится.
 * Очередь живёт в памяти: после перезапуска ядра команду отправляют заново, уже размеченные
 * карточки пропускаются без запроса.
 */
@Component
public class ProdSpecsLabelQueue {

    private static final Logger log = LoggerFactory.getLogger(ProdSpecsLabelQueue.class);

    private final ProdSpecsCardLabeler labeler;
    private final long pauseMs;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "prod-specs-label");
        thread.setDaemon(true);
        return thread;
    });

    public ProdSpecsLabelQueue(ProdSpecsCardLabeler labeler,
                               @Value("${taskmanager.prod_specs.pause-ms:1000}") long pauseMs) {
        this.labeler = labeler;
        this.pauseMs = pauseMs;
    }

    /** Поставить карточки в очередь. Возвращает число принятых (не стоявших в очереди). */
    public int enqueue(Collection<UUID> cardIds) {
        List<UUID> accepted = new ArrayList<>();
        for (UUID cardId : cardIds) {
            if (pending.add(cardId)) {
                accepted.add(cardId);
            }
        }
        if (!accepted.isEmpty()) {
            executor.execute(() -> {
                try {
                    processBatch(accepted);
                } finally {
                    pending.removeAll(accepted);
                }
            });
        }
        return accepted.size();
    }

    /**
     * Разметить пачку по порядку, с паузой после каждого обращения к модели.
     * Ошибка одной карточки остальных не останавливает; ошибка авторизации останавливает пачку.
     */
    public Map<LabelResult, Integer> processBatch(List<UUID> cardIds) {
        Map<LabelResult, Integer> stats = new EnumMap<>(LabelResult.class);
        for (UUID cardId : cardIds) {
            LabelResult result;
            try {
                result = labeler.label(cardId);
            } catch (LlmException e) {
                log.error("Признаки карточек: пачка остановлена ({}): {}", e.getKind(), e.getMessage());
                break;
            } catch (RuntimeException e) {
                log.error("Признаки карточки card_id={}: ошибка разметки: {}", cardId, e.getMessage(), e);
                result = LabelResult.FAILED;
            }
            stats.merge(result, 1, Integer::sum);
            boolean modelWasCalled = result == LabelResult.LABELED || result == LabelResult.FAILED;
            if (modelWasCalled && !pause()) {
                break;
            }
        }
        log.info("Признаки карточек: пачка из {} обработана: {}", cardIds.size(), stats);
        return stats;
    }

    private boolean pause() {
        if (pauseMs <= 0) {
            return true;
        }
        try {
            Thread.sleep(pauseMs);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}