package com.mercator.taskmanager.controller;

import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.service.HierarchicalSetImportService;
import com.mercator.taskmanager.service.SetFillRedisService;
import com.mercator.taskmanager.service.SetFillService;
import com.mercator.taskmanager.service.SetImportService;
import com.mercator.taskmanager.set_csv.KidsStratumCsvParser;
import com.mercator.taskmanager.set_csv.KidsStratumRow;
import com.mercator.taskmanager.set_csv.StratumCsvParser;
import com.mercator.taskmanager.set_csv.StratumRow;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Приём CSV со стратами сета (админка).
 *
 * POST /api/v1/sets/import — плоский CSV (одна строка = одна страта),
 *   формат категории «одежда».
 * POST /api/v1/sets/import-hierarchical — иерархический CSV
 *   (подкатегории + предметы внутри них), формат детской одежды.
 *
 * Оба принимают multipart-форму:
 *   file        — CSV-файл
 *   marketplace — Ozon
 *   category    — clothing / kids_clothing
 *   geo         — Санкт-Петербург
 *
 * Соединяет три слоя: парсер CSV → сервис импорта → БД.
 */
@RestController
@RequestMapping("/api/v1/sets")
public class SetImportController {

    private final StratumCsvParser parser;
    private final KidsStratumCsvParser hierarchicalParser;
    private final SetImportService importService;
    private final HierarchicalSetImportService hierarchicalImportService;
    private final SetFillService fillService;
    private final SetFillRedisService fillRedisService;

    public SetImportController(StratumCsvParser parser,
                               KidsStratumCsvParser hierarchicalParser,
                               SetImportService importService,
                               HierarchicalSetImportService hierarchicalImportService,
                               SetFillService fillService,
                               SetFillRedisService fillRedisService) {
        this.parser = parser;
        this.hierarchicalParser = hierarchicalParser;
        this.importService = importService;
        this.hierarchicalImportService = hierarchicalImportService;
        this.fillService = fillService;
        this.fillRedisService = fillRedisService;
    }

    /** Плоский CSV: одна строка — одна страта (категория «одежда»). */
    @PostMapping("/import")
    public ResponseEntity<?> importSet(
            @RequestParam("file") MultipartFile file,
            @RequestParam("marketplace") String marketplace,
            @RequestParam("category") String category,
            @RequestParam("geo") String geo) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой"));
        }

        try {
            // 1. Разбираем CSV.
            List<StratumRow> rows = parser.parse(file.getInputStream());

            // 2. Импортируем как новый сет.
            SetEntity set = importService.importSet(marketplace, category, geo, rows);

            // 3. Отдаём id созданного сета и число страт.
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "setId", set.getId().toString(),
                    "marketplace", set.getMarketplace().getTitle(),
                    "category", set.getCategory().getTitle(),
                    "geo", set.getGeo().getTitle(),
                    "strataCount", rows.size()
            ));
        } catch (IllegalArgumentException e) {
            // Ошибки разбора CSV или валидации — понятный ответ с текстом.
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Не удалось прочитать файл: " + e.getMessage()));
        }
    }

    /**
     * Иерархический CSV: строки подкатегорий чередуются со строками предметов.
     * Количество карточек предмета считается при разборе как
     * итог подкатегории × доля запроса (математическое округление).
     */
    @PostMapping("/import-hierarchical")
    public ResponseEntity<?> importHierarchicalSet(
            @RequestParam("file") MultipartFile file,
            @RequestParam("marketplace") String marketplace,
            @RequestParam("category") String category,
            @RequestParam("geo") String geo) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Файл пустой"));
        }

        try {
            List<KidsStratumRow> rows = hierarchicalParser.parse(file.getInputStream());
            SetEntity set = hierarchicalImportService.importSet(marketplace, category, geo, rows);

            int plannedCards = rows.stream().mapToInt(KidsStratumRow::getCount).sum();
            long subcategories = rows.stream()
                    .map(r -> r.getSubcategoryTitle() + "|" + r.getSegment() + "|" + r.getLayer())
                    .distinct()
                    .count();

            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "setId", set.getId().toString(),
                    "marketplace", set.getMarketplace().getTitle(),
                    "category", set.getCategory().getTitle(),
                    "geo", set.getGeo().getTitle(),
                    "subcategoryCount", subcategories,
                    "strataCount", rows.size(),
                    "plannedCards", plannedCards
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Не удалось прочитать файл: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/fill")
    public ResponseEntity<?> fillSet(@PathVariable("id") UUID setId) {
        try {
            // запускаем наполнение сета
            int totalCards = fillService.fillSet(setId);
            return ResponseEntity.ok(Map.of(
                    "setId", setId.toString(),
                    "totalCards", totalCards,
                    "status", "success",
                    "message", "Сет успешно наполнен карточками"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(),
                    "setId", setId.toString()
            ));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Ошибка при наполнении сета: " + e.getMessage(),
                            "setId", setId.toString()
                    ));
        }
    }

    @PostMapping("/{id}/fill-async")
    public ResponseEntity<?> fillSetAsync(@PathVariable("id") UUID setId) {
        try {
            int sent = fillRedisService.startFill(setId);
            return ResponseEntity.accepted().body(Map.of(
                    "setId", setId.toString(),
                    "tasksSent", sent,
                    "status", "accepted",
                    "message", "Наполнение запущено, задачи отправлены в очередь"
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", e.getMessage(), "setId", setId.toString()));
        }
    }
}