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

/**
 * Автодобор ниш (ниша остаётся active).
 *
 * fillDeficits:
 *  1. Уборка: выбывшие по жизненному циклу карточки состава — из ниши,
 *     их кандидаты — unavailable. Проверки без результата дольше
 *     inflight-timeout снимаются.
 *  2. Выбор — только после результата поиска автодобора: эмбеддинги новых
 *     кандидатов, проход по всем кандидатам по убыванию близости; новые и
 *     обходящиеся карточки — сразу, исторические — проверочный парсинг.
 *
 * startSearches: при дефиците (plan-count − активные в составе − кандидаты
 * на проверке) — поиск на дефицит × candidate-multiplier, не чаще
 * refill-interval-hours по нише. Задача без результата дольше
 * select-timeout-hours считается потерянной.
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
        this.selectTimeoutHours = selectTimeoutHours;
        this.verifyTimeoutMinutes = verifyTimeoutMinutes;
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

        // ---------- 2. Выбор — только после результата поиска ----------
        if (niche.getSelectTaskId() != null || niche.getRefillRequestedAt() == null) {
            return;
        }
        long active = cardRepository.countActiveInNiche(niche.getId());
        long verifying = candidateRepository.countByNicheIdAndVerifyTaskIdIsNotNull(niche.getId());
        long slots = niche.getPlanCount() - active - verifying;
        if (slots <= 0) {
            return;
        }

        if (niche.getReferenceSnapshot() == null) {
            return;  // ниша без референса — подбор аналогов невозможен
        }
        Ranked ranked = rankingService.score(niche);
        RefillResult r = store.applyRefill(niche.getId(), ranked.choices(),
                rankingService.duplicateFilter(ranked), (int) slots, niche.getRefillRequestedAt());

        // ---------- 3. Проверочный парсинг: исторические кандидаты и подставленные размеры ----------
        rankingService.sendVerifyTasks(niche, r.toVerify());

        if (r.taken() + r.unavailable() + r.duplicates() + r.toVerify().size() > 0) {
            log.info("Ниша id={}: автодобор — мест {}, взято сразу {}, на проверку {}, unavailable {}, "
                            + "дублей {} (новых векторов {})",
                    niche.getId(), slots, r.taken(), r.toVerify().size(), r.unavailable(),
                    r.duplicates(), ranked.embedded());
        }
    }

    /** Отправить поиск новых кандидатов нишам с дефицитом. */
    public void startSearches() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime intervalThreshold = now.minusHours(refillIntervalHours);
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

            // ---------- 3. Интервал между поисками ----------
            if (niche.getRefillRequestedAt() != null
                    && niche.getRefillRequestedAt().isAfter(intervalThreshold)) {
                continue;
            }

            log.info("Ниша id={}: дефицит {} (в составе активных {}, на проверке {}, нужно {})",
                    niche.getId(), deficit, active, verifying, niche.getPlanCount());
            selectionService.sendRefillTask(niche, (int) deficit * candidateMultiplier);
        }
    }
}