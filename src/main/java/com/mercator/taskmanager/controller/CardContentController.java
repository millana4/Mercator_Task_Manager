package com.mercator.taskmanager.controller;

import com.mercator.taskmanager.service.CardContentBackfillService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ручной запуск добора полного набора карточек (content_txt):
 * POST /api/v1/cards/content-backfill[?limit=N] → {"enqueued": N}.
 */
@RestController
@RequestMapping("/api/v1/cards")
public class CardContentController {

    private final CardContentBackfillService backfillService;

    public CardContentController(CardContentBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @PostMapping("/content-backfill")
    public BackfillResponse contentBackfill(@RequestParam(required = false) Integer limit) {
        return new BackfillResponse(backfillService.enqueue(limit));
    }

    public record BackfillResponse(int enqueued) {
    }
}