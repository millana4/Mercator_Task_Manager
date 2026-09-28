package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.repository.NicheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Планировщик ниш: продвигает ниши по статусам.
 *
 * new со ссылкой       → отправить парсинг референса (reference_parsing)
 * new без ссылки       → query_generation
 * reference_parsing:
 *   task_id сброшен (антибот)            → отправить повторно
 *   результат не пришёл за inflight-окно → отправить повторно
 *
 * Состояние хранится в базе: перезапуск ядра посреди обработки ничего не теряет.
 */
@Component
public class NicheScheduler {

    private static final Logger log = LoggerFactory.getLogger(NicheScheduler.class);

    private final NicheRepository nicheRepository;
    private final NicheReferenceService referenceService;
    private final long referenceTimeoutMinutes;

    public NicheScheduler(NicheRepository nicheRepository,
                          NicheReferenceService referenceService,
                          @Value("${taskmanager.monitoring.inflight-timeout-minutes:45}")
                          long referenceTimeoutMinutes) {
        this.nicheRepository = nicheRepository;
        this.referenceService = referenceService;
        this.referenceTimeoutMinutes = referenceTimeoutMinutes;
    }

    @Scheduled(fixedDelayString = "${taskmanager.niche.scheduler-interval-ms:30000}")
    public void tick() {
        try {
            startNewNiches();
            retryReferenceParsing();
        } catch (Exception e) {
            // Не роняем планировщик из-за одной ошибки — следующий тик повторит.
            log.error("Ошибка планировщика ниш: {}", e.getMessage(), e);
        }
    }

    // ---------- 1. Новые ниши ----------
    private void startNewNiches() {
        for (NicheEntity niche : nicheRepository.findByStatus(NicheStatus.NEW)) {
            if (niche.getReferenceSku() != null) {
                referenceService.sendReferenceTask(niche);
            } else {
                niche.setStatus(NicheStatus.QUERY_GENERATION);
                nicheRepository.save(niche);
                log.info("Ниша id={}: ссылки нет, переход к генерации запроса по описанию",
                        niche.getId());
            }
        }
    }

    // ---------- 2. Повтор парсинга референса ----------
    private void retryReferenceParsing() {
        OffsetDateTime threshold = OffsetDateTime.now().minusMinutes(referenceTimeoutMinutes);
        for (NicheEntity niche : nicheRepository.findByStatus(NicheStatus.REFERENCE_PARSING)) {
            if (niche.getReferenceTaskId() == null) {
                log.info("Ниша id={}: повтор парсинга референса после антибота", niche.getId());
                referenceService.sendReferenceTask(niche);
            } else if (niche.getUpdatedAt().isBefore(threshold)) {
                log.warn("Ниша id={}: результат референса не пришёл за {} мин, повтор (task_id={})",
                        niche.getId(), referenceTimeoutMinutes, niche.getReferenceTaskId());
                referenceService.sendReferenceTask(niche);
            }
        }
    }
}