package com.mercator.taskmanager.service;

import com.mercator.taskmanager.analytics.AnalyticsClient;
import com.mercator.taskmanager.analytics.AnalyticsClient.AlgorithmState;
import com.mercator.taskmanager.analytics.AnalyticsClient.Readiness;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.SetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Проверка готовности сетов в сборе — через 30 мин после батча аналитики
 * (00:45 / 12:45 → 01:15 / 13:15 МСК). Аналитика ответила, что MACD уровня категории
 * по сету выполнился, — категория переключается на этот сет (SeasonSwitchService).
 * Аналитика недоступна — сет ждёт следующей проверки.
 */
@Component
public class SeasonSwitchScheduler {

    private static final Logger log = LoggerFactory.getLogger(SeasonSwitchScheduler.class);

    private final SetRepository setRepository;
    private final AnalyticsClient analytics;
    private final SeasonSwitchService switchService;
    private final boolean enabled;

    public SeasonSwitchScheduler(SetRepository setRepository,
                                 AnalyticsClient analytics,
                                 SeasonSwitchService switchService,
                                 @Value("${taskmanager.season.enabled:true}") boolean enabled) {
        this.setRepository = setRepository;
        this.analytics = analytics;
        this.switchService = switchService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${taskmanager.season.switch-cron:0 15 1,13 * * *}", zone = "Europe/Moscow")
    public void tick() {
        if (enabled) {
            checkAll();
        }
    }

    /** Проверить все сеты в сборе. Возвращает число переключений. */
    public int checkAll() {
        int switched = 0;
        for (SetEntity set : setRepository.findByStatus(SetStatus.COLLECTING)) {
            try {
                Readiness readiness = analytics.setReadiness(set.getId());
                if (!readiness.readyForSwitch()) {
                    log.info("Сет {} в сборе: EWMA — {}, MACD — {}; переключение позже",
                            set.getId(), describe(readiness.algorithm("EWMA")),
                            describe(readiness.algorithm("MACD")));
                    continue;
                }
                switchService.switchTo(set.getId());
                switched++;
            } catch (RuntimeException e) {
                log.error("Готовность сета {} не проверена: {}", set.getId(), e.getMessage(), e);
            }
        }
        return switched;
    }

    private static String describe(AlgorithmState state) {
        if (state == null) {
            return "не считался";
        }
        return (Boolean.TRUE.equals(state.sufficient()) ? "выполнился" : "не выполнился")
                + ", карточек " + state.nCards();
    }
}