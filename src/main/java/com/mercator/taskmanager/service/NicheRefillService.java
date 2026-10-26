package com.mercator.taskmanager.service;

import com.mercator.taskmanager.embedding.EmbedderException;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import com.mercator.taskmanager.service.NicheRankingStore.RefillResult;
import com.mercator.taskmanager.service.NicheRankingService.Ranked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * Автодобор ниш (ниша остаётся active).
 *
 * fillDeficits:
 *  1. Уборка: выбывшие по жизненному циклу карточки состава — из ниши,
 *     их кандидаты — unavailable. Проверки без результата дольше
 *     inflight-timeout снимаются. Освободились места - есть что выбирать.
 *  2. Выбор — только когда есть что выбирать (refill_choice_pending: новые кандидаты
 *     поиска или освободившиеся места): эмбеддинги новых кандидатов, проход по всем
 *     кандидатам по убыванию близости; новые и обходящиеся карточки — сразу,
 *     исторические — проверочный парсинг. После выбора флаг снимается.
 *
 * startSearches: при дефиците (plan-count − активные в составе − кандидаты
 * на проверке) — поиск на дефицит × candidate-multiplier. Интервал между поисками
 * по нише - refill-interval-hours; после поисков подряд без новых кандидатов
 * (выдача исчерпана) - ступени refill-empty-backoff-hours (24 ч, затем 72 ч).
 * Задача без результата дольше select-timeout-hours считается потерянной.
 */
@Service
public class NicheRefillService {

    private static final Logger log = LoggerFactory.getLogger(NicheRefillService.class);

    private final NicheRepository nicheRepository;
    private final NicheCandidateRepository candidateRepository;
    private final CardRepository cardRepository;
    private final NicheSelectionService selectionService;
    private final NicheRankingService rankingService;
    private final NicheRankingStore store;
    private final int candidateMultiplier;
    private final long refillIntervalHours;
    private final long[] emptyBackoffHours;
    private final long selectTimeoutHours;
    private final long verifyTimeoutMinutes;

    public NicheRefillService(NicheRepository nicheRepository,
                              NicheCandidateRepository candidateRepository,
                              CardRepository cardRepository,
                              NicheSelectionService selectionService,
                              NicheRankingService rankingService,
                              NicheRankingStore store,
                              @Value("${taskmanager.niche.candidate-multiplier:2}")
                              int candidateMultiplier,
                              @Value("${taskmanager.niche.refill-interval-hours:6}")
                              long refillIntervalHours,
                              @Value("${taskmanager.niche.refill-empty-backoff-hours:24,72}")
                              String emptyBackoffHours,
                              @Value("${taskmanager.niche.select-timeout-hours:3}")
                              long selectTimeoutHours,
                              @Value("${taskmanager.monitoring.inflight-timeout-minutes:45}")
                              long verifyTimeoutMinutes) {
        this.nicheRepository = nicheRepository;
        this.candidateRepository = candidateRepository;
        this.cardRepository = cardRepository;
        this.selectionService = selectionService;
        this.rankingService = rankingService;
        this.store = store;
        this.candidateMultiplier = candidateMultiplier;
        this.refillIntervalHours = refillIntervalHours;
        this.emptyBackoffHours = parseHours(emptyBackoffHours);
        this.selectTimeoutHours = selectTimeoutHours;
        this.verifyTimeoutMinutes = verifyTimeoutMinutes;
    }

    /** "24, 72" -> {24, 72}; пустая строка - ступеней нет (всегда обычный интервал). */
    static long[] parseHours(String value) {
        if (value == null || value.isBlank()) {
            return new long[0];
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                .mapToLong(Long::parseLong).toArray();
    }

    /**
     * Интервал до следующего поиска: emptyCount поисков подряд без новых кандидатов.
     * 0 - обычный интервал; 1, 2, ... - ступени backoff, после последней ступени - она же.
     */
    static long searchIntervalHours(int emptyCount, long baseHours, long[] backoff) {
        if (emptyCount <= 0 || backoff.length == 0) {
            return baseHours;
        }
        return backoff[Math.min(emptyCount, backoff.length) - 1];
    }

    /** Уборка выбывших и выбор кандидатов для ниш с дефицитом. */
    public void fillDeficits() {
        for (NicheEntity niche : nicheRepository.findByStatus(NicheStatus.ACTIVE)) {
            try {
                fillDeficit(niche);
            } catch (EmbedderException e) {
                log.warn("Ниша id={}: эмбеддер недоступен, автодобор повторится на следующем тике: {}",
                        niche.getId(), e.getMessage());
            } catch (RuntimeException e) {
                log.error("Ниша id={}: ошибка автодобора, повтор на следующем тике: {}",
                        niche.getId(), e.getMessage(), e);
            }
        }
    }

    private void fillDeficit(NicheEntity niche) {
        boolean pending = niche.isRefillChoicePending();

        // ---------- 1. Уборка выбывших ----------
        int cleaned = store.cleanupDroppedMembers(niche.getId());
        if (cleaned > 0) {
            log.info("Ниша id={}: из состава убрано выбывших карточек {}", niche.getId(), cleaned);
        }

        // ---------- 1а. Потерянные проверки ----------
        int released = store.releaseStaleVerifications(niche.getId(),
                OffsetDateTime.now().minusMinutes(verifyTimeoutMinutes));
        if (released > 0) {
            log.warn("Ниша id={}: снято проверок без результата за {} мин: {}",
                    niche.getId(), verifyTimeoutMinutes, released);
        }

        // Освободились места - их можно заполнить уже известными кандидатами, не дожидаясь
        // следующего поиска (у узкой ниши он может быть через 72 ч). До первого поиска
        // автодобора выбора нет: сначала поиск новых кандидатов.
        boolean searched = niche.getRefillRequestedAt() != null;
        if ((cleaned > 0 || released > 0) && searched && !pending) {
            store.setRefillChoicePending(niche.getId(), true);
            pending = true;
        }

        // ---------- 2. Выбор - только после поиска и когда есть что выбирать ----------
        if (niche.getSelectTaskId() != null || !searched || !pending) {
            return;
        }
        long active = cardRepository.countActiveInNiche(niche.getId());
        long verifying = candidateRepository.countByNicheIdAndVerifyTaskIdIsNotNull(niche.getId());
        long slots = niche.getPlanCount() - active - verifying;
        if (slots <= 0 || niche.getReferenceSnapshot() == null) {
            // мест нет или ниша без референса (подбор аналогов невозможен) - выбирать нечего
            store.setRefillChoicePending(niche.getId(), false);
            return;
        }

        Ranked ranked = rankingService.score(niche);
        RefillResult r = store.applyRefill(niche.getId(), ranked.choices(),
                rankingService.duplicateFilter(ranked), (int) slots, niche.getRefillRequestedAt());
        store.setRefillChoicePending(niche.getId(), false);

        // ---------- 3. Проверочный парсинг: исторические кандидаты и подставленные размеры ----------
        rankingService.sendVerifyTasks(niche, r.toVerify());

        log.info("Ниша id={}: автодобор — мест {}, взято сразу {}, на проверку {}, unavailable {}, "
                        + "дублей {} (новых векторов {})",
                niche.getId(), slots, r.taken(), r.toVerify().size(), r.unavailable(),
                r.duplicates(), ranked.embedded());
    }

    /** Отправить поиск новых кандидатов нишам с дефицитом. */
    public void startSearches() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime timeoutThreshold = now.minusHours(selectTimeoutHours);

        for (NicheEntity niche : nicheRepository.findByStatus(NicheStatus.ACTIVE)) {
            // ---------- 1. Поиск уже в работе ----------
            if (niche.getSelectTaskId() != null) {
                if (niche.getRefillRequestedAt() != null
                        && niche.getRefillRequestedAt().isBefore(timeoutThreshold)) {
                    log.warn("Ниша id={}: результат поиска автодобора не пришёл за {} ч, задача "
                                    + "считается потерянной (task_id={})",
                            niche.getId(), selectTimeoutHours, niche.getSelectTaskId());
                    niche.setSelectTaskId(null);
                    nicheRepository.save(niche);
                }
                continue;
            }

            // ---------- 2. Дефицит ----------
            long active = cardRepository.countActiveInNiche(niche.getId());
            long verifying = candidateRepository.countByNicheIdAndVerifyTaskIdIsNotNull(niche.getId());
            long deficit = niche.getPlanCount() - active - verifying;
            if (deficit <= 0) {
                continue;
            }

            // ---------- 3. Интервал между поисками: длиннее, если выдача исчерпана ----------
            long intervalHours = searchIntervalHours(niche.getRefillEmptyCount(),
                    refillIntervalHours, emptyBackoffHours);
            if (niche.getRefillRequestedAt() != null
                    && niche.getRefillRequestedAt().isAfter(now.minusHours(intervalHours))) {
                continue;
            }

            log.info("Ниша id={}: дефицит {} (в составе активных {}, на проверке {}, нужно {}; "
                            + "поисков подряд без новых {}, интервал {} ч)",
                    niche.getId(), deficit, active, verifying, niche.getPlanCount(),
                    niche.getRefillEmptyCount(), intervalHours);
            selectionService.sendRefillTask(niche, (int) deficit * candidateMultiplier);
        }
    }
}