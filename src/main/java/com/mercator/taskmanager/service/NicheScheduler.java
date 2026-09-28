package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheLlmLogEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.repository.NicheLlmLogRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Optional;

/**
 * Планировщик ниш: продвигает ниши по статусам.
 *
 * new со ссылкой       → отправить парсинг референса (reference_parsing)
 * new без ссылки       → query_generation
 * reference_parsing:
 *   task_id сброшен (антибот)            → отправить повторно
 *   результат не пришёл за inflight-окно → отправить повторно
 * query_generation     → нейросеть: запрос + страты → selecting;
 *                        неудача — повтор через паузу, после лимита попыток — failed
 *
 * Состояние хранится в базе: перезапуск ядра посреди обработки ничего не теряет.
 */
@Component
public class NicheScheduler {

    private static final Logger log = LoggerFactory.getLogger(NicheScheduler.class);

    private final NicheRepository nicheRepository;
    private final NicheReferenceService referenceService;
    private final NicheQueryGenerator queryGenerator;
    private final NicheLlmLogRepository llmLogRepository;
    private final long referenceTimeoutMinutes;
    private final int llmMaxAttempts;
    private final long llmRetryDelayMinutes;

    public NicheScheduler(NicheRepository nicheRepository,
                          NicheReferenceService referenceService,
                          NicheQueryGenerator queryGenerator,
                          NicheLlmLogRepository llmLogRepository,
                          @Value("${taskmanager.monitoring.inflight-timeout-minutes:45}")
                          long referenceTimeoutMinutes,
                          @Value("${taskmanager.niche.llm-max-attempts:3}")
                          int llmMaxAttempts,
                          @Value("${taskmanager.niche.llm-retry-delay-minutes:5}")
                          long llmRetryDelayMinutes) {
        this.nicheRepository = nicheRepository;
        this.referenceService = referenceService;
        this.queryGenerator = queryGenerator;
        this.llmLogRepository = llmLogRepository;
        this.referenceTimeoutMinutes = referenceTimeoutMinutes;
        this.llmMaxAttempts = llmMaxAttempts;
        this.llmRetryDelayMinutes = llmRetryDelayMinutes;
    }

    @Scheduled(fixedDelayString = "${taskmanager.niche.scheduler-interval-ms:30000}")
    public void tick() {
        try {
            startNewNiches();
            retryReferenceParsing();
            generateQueries();
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

    // ---------- 3. Генерация поискового запроса ----------
    private void generateQueries() {
        OffsetDateTime retryThreshold = OffsetDateTime.now().minusMinutes(llmRetryDelayMinutes);

        for (NicheEntity niche : nicheRepository.findByStatus(NicheStatus.QUERY_GENERATION)) {
            // Пауза после неудачного обращения
            Optional<NicheLlmLogEntity> last =
                    llmLogRepository.findTopByNicheIdOrderByCreatedAtDesc(niche.getId());
            if (last.isPresent() && !last.get().getSuccess()
                    && last.get().getCreatedAt().isAfter(retryThreshold)) {
                continue;
            }

            NicheQueryResult result;
            try {
                result = queryGenerator.generate(niche);
            } catch (LlmException e) {
                handleQueryFailure(niche, e);
                continue;
            }

            // Обращение длится до минуты: перечитываем нишу, чтобы не затереть
            // изменения за это время (например, удаление пользователем).
            NicheEntity fresh = nicheRepository.findById(niche.getId()).orElse(null);
            if (fresh == null || !NicheStatus.QUERY_GENERATION.equals(fresh.getStatus())) {
                log.info("Ниша id={}: запрос сгенерирован, но статус уже {} — результат не сохраняется",
                        niche.getId(), fresh == null ? "удалена" : fresh.getStatus());
                continue;
            }

            fresh.setSearchQuery(result.searchQuery());
            fresh.setHintedStrata(new HashSet<>(result.strata()));
            fresh.setStatus(NicheStatus.SELECTING);
            fresh.setError(null);
            nicheRepository.save(fresh);

            log.info("Ниша id={}: запрос «{}», страт-подсказок {}, переход к подбору "
                            + "(model={}, tokens={})",
                    fresh.getId(), result.searchQuery(), result.strata().size(),
                    result.model(), result.tokens());
        }
    }

    private void handleQueryFailure(NicheEntity niche, LlmException e) {
        long failures = llmLogRepository.countByNicheIdAndSuccessFalse(niche.getId());
        if (failures < llmMaxAttempts) {
            log.warn("Ниша id={}: генерация запроса не удалась (попытка {} из {}), "
                            + "повтор через {} мин: {}",
                    niche.getId(), failures, llmMaxAttempts, llmRetryDelayMinutes, e.getMessage());
            return;
        }

        NicheEntity fresh = nicheRepository.findById(niche.getId()).orElse(null);
        if (fresh == null || !NicheStatus.QUERY_GENERATION.equals(fresh.getStatus())) {
            return;
        }
        fresh.setStatus(NicheStatus.FAILED);
        fresh.setError("Не удалось сгенерировать поисковый запрос: " + e.getMessage());
        nicheRepository.save(fresh);
        log.error("Ниша id={}: генерация запроса не удалась {} раз — ниша failed: {}",
                niche.getId(), failures, e.getMessage());
    }
}