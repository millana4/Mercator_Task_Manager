package com.mercator.taskmanager.controller;

import com.mercator.taskmanager.assignment.AssignmentViews.Overview;
import com.mercator.taskmanager.assignment.AssignmentViews.Progress;
import com.mercator.taskmanager.assignment.AssignmentViews.SetView;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.service.AssignmentViewService;
import com.mercator.taskmanager.service.SeasonalSetService;
import com.mercator.taskmanager.service.SeasonalSetService.DraftChange;
import com.mercator.taskmanager.service.SeasonalSetService.StratumChange;
import com.mercator.taskmanager.service.SeasonalSetService.SubcategoryChange;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Админка задания: просмотр сетов связки и задания, черновик нового сезонного сета,
 * правка черновика, запуск сбора. Закрыта межсервисным ключом (NicheApiKeyFilter):
 * вызывает шлюз сервиса аналитики, проверивший роль администратора.
 *
 * GET    /api/v1/admin/overview?category=&geo=&marketplace=   — сеты связки и ход сбора
 * GET    /api/v1/admin/sets/{id}                               — задание сета
 * GET    /api/v1/admin/sets/{id}/progress                      — ход сбора
 * POST   /api/v1/admin/sets/{activeId}/draft                   — черновик копией активного (201)
 * PATCH  /api/v1/admin/drafts/{id}                             — сезон и параметры
 * POST   /api/v1/admin/drafts/{id}/subcategories               — новая подкатегория (201)
 * PATCH  /api/v1/admin/drafts/{id}/subcategories/{subId}       — правка подкатегории
 * DELETE /api/v1/admin/drafts/{id}/subcategories/{subId}       — удалить подкатегорию
 * POST   /api/v1/admin/drafts/{id}/subcategories/{subId}/strata — новая страта (201)
 * PATCH  /api/v1/admin/drafts/{id}/strata/{stratumId}          — правка страты
 * DELETE /api/v1/admin/drafts/{id}/strata/{stratumId}          — удалить страту
 * POST   /api/v1/admin/drafts/{id}/start                       — запуск сбора
 * DELETE /api/v1/admin/drafts/{id}                             — удалить черновик (204)
 *
 * Правки возвращают обновлённое задание черновика (с пересчитанными количествами).
 * Ошибки: сета или строки нет — 404, неверное состояние или значение — 409;
 * в теле {"error": текст для журнала} — тексты интерфейса во фронтенде.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AssignmentViewService views;
    private final SeasonalSetService seasonal;

    public AdminController(AssignmentViewService views, SeasonalSetService seasonal) {
        this.views = views;
        this.seasonal = seasonal;
    }

    // ---------- Просмотр ----------

    @GetMapping("/overview")
    public Overview overview(@RequestParam("category") String category,
                             @RequestParam("geo") String geo,
                             @RequestParam("marketplace") String marketplace) {
        return views.overview(category, geo, marketplace);
    }

    @GetMapping("/sets/{id}")
    public SetView set(@PathVariable("id") UUID setId) {
        return views.view(setId);
    }

    @GetMapping("/sets/{id}/progress")
    public Progress progress(@PathVariable("id") UUID setId) {
        return views.progress(setId);
    }

    // ---------- Черновик ----------

    @PostMapping("/sets/{id}/draft")
    public ResponseEntity<SetView> createDraft(@PathVariable("id") UUID activeSetId) {
        SetEntity draft = seasonal.createDraft(activeSetId);
        return ResponseEntity.status(HttpStatus.CREATED).body(views.view(draft.getId()));
    }

    @PatchMapping("/drafts/{id}")
    public SetView updateDraft(@PathVariable("id") UUID draftId, @RequestBody DraftChange change) {
        seasonal.updateDraft(draftId, change);
        return views.view(draftId);
    }

    @PostMapping("/drafts/{id}/subcategories")
    public ResponseEntity<SetView> addSubcategory(@PathVariable("id") UUID draftId,
                                                  @RequestBody SubcategoryChange change) {
        seasonal.addSubcategory(draftId, change);
        return ResponseEntity.status(HttpStatus.CREATED).body(views.view(draftId));
    }

    @PatchMapping("/drafts/{id}/subcategories/{subId}")
    public SetView updateSubcategory(@PathVariable("id") UUID draftId,
                                     @PathVariable("subId") UUID subcategoryId,
                                     @RequestBody SubcategoryChange change) {
        seasonal.updateSubcategory(draftId, subcategoryId, change);
        return views.view(draftId);
    }

    @DeleteMapping("/drafts/{id}/subcategories/{subId}")
    public SetView deleteSubcategory(@PathVariable("id") UUID draftId,
                                     @PathVariable("subId") UUID subcategoryId) {
        seasonal.deleteSubcategory(draftId, subcategoryId);
        return views.view(draftId);
    }

    @PostMapping("/drafts/{id}/subcategories/{subId}/strata")
    public ResponseEntity<SetView> addStratum(@PathVariable("id") UUID draftId,
                                              @PathVariable("subId") UUID subcategoryId,
                                              @RequestBody StratumChange change) {
        seasonal.addStratum(draftId, subcategoryId, change);
        return ResponseEntity.status(HttpStatus.CREATED).body(views.view(draftId));
    }

    @PatchMapping("/drafts/{id}/strata/{stratumId}")
    public SetView updateStratum(@PathVariable("id") UUID draftId,
                                 @PathVariable("stratumId") UUID stratumId,
                                 @RequestBody StratumChange change) {
        seasonal.updateStratum(draftId, stratumId, change);
        return views.view(draftId);
    }

    @DeleteMapping("/drafts/{id}/strata/{stratumId}")
    public SetView deleteStratum(@PathVariable("id") UUID draftId,
                                 @PathVariable("stratumId") UUID stratumId) {
        seasonal.deleteStratum(draftId, stratumId);
        return views.view(draftId);
    }

    @PostMapping("/drafts/{id}/start")
    public SetView start(@PathVariable("id") UUID draftId) {
        seasonal.startCollecting(draftId);
        return views.view(draftId);
    }

    @DeleteMapping("/drafts/{id}")
    public ResponseEntity<Void> deleteDraft(@PathVariable("id") UUID draftId) {
        seasonal.deleteDraft(draftId);
        return ResponseEntity.noContent().build();
    }

    // ---------- Ошибки ----------

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> notFound(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> conflict(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}