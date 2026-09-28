package com.mercator.taskmanager.controller;

import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.service.NicheService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Задания товарных ниш от сервиса аналитики.
 *
 * POST   /api/v1/niches              — принять задание (202), повторная отправка идемпотентна
 * DELETE /api/v1/niches/{externalId} — удалить нишу (мягко)
 */
@RestController
@RequestMapping("/api/v1/niches")
public class NicheController {

    private final NicheService nicheService;

    public NicheController(NicheService nicheService) {
        this.nicheService = nicheService;
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody NicheCreateRequest request) {
        try {
            NicheEntity niche = nicheService.create(request);
            return ResponseEntity.accepted().body(Map.of(
                    "nicheId", niche.getId().toString(),
                    "externalId", niche.getExternalId().toString(),
                    "status", niche.getStatus()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/{externalId}")
    public ResponseEntity<?> delete(@PathVariable("externalId") UUID externalId) {
        if (!nicheService.delete(externalId)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "Ниша не найдена: " + externalId));
        }
        return ResponseEntity.ok(Map.of(
                "externalId", externalId.toString(),
                "status", NicheStatus.DELETED
        ));
    }
}