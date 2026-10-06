package com.mercator.taskmanager.controller;

import com.mercator.taskmanager.service.SearchSnapshotScheduler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/** Ручной запуск снимка выдачи по категории (проверка, повтор прогона после сбоя). */
@RestController
@RequestMapping("/api/v1/search-snapshots")
public class SearchSnapshotController {

    private final SearchSnapshotScheduler scheduler;

    public SearchSnapshotController(SearchSnapshotScheduler scheduler) {
        this.scheduler = scheduler;
    }

    @PostMapping("/run")
    public Map<String, Object> run(@RequestParam String category) {
        OffsetDateTime runAt = ZonedDateTime.now(SearchSnapshotScheduler.MSK)
                .truncatedTo(ChronoUnit.MINUTES).toOffsetDateTime();
        int sent = scheduler.run(category, runAt);
        return Map.of("category", category, "run_at", runAt.toString(), "tasks", sent);
    }
}