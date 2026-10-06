package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.clickhouse.SearchSnapshotRepository;
import com.mercator.taskmanager.clickhouse.SearchSnapshotRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Результат снимка выдачи -> строки search_snapshot в ClickHouse.
 * Неудачный снимок (капча, ошибка) только логируется: пропуск одной страты
 * в одном прогоне не мешает сравнению выдач.
 */
@Component
public class SearchResultConsumer {

    private static final Logger log = LoggerFactory.getLogger(SearchResultConsumer.class);
    private static final UUID NO_ID = new UUID(0L, 0L);

    private final SearchSnapshotRepository repository;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public SearchResultConsumer(SearchSnapshotRepository repository) {
        this.repository = repository;
    }

    public void handle(String json) {
        SearchResultMessage r;
        try {
            r = mapper.readValue(json, SearchResultMessage.class);
        } catch (Exception e) {
            log.error("Не разобрать результат снимка выдачи, сообщение пропущено: {}", e.getMessage());
            return;
        }
        if (!Boolean.TRUE.equals(r.getOk())) {
            log.warn("Снимок выдачи не получен: task_id={} stratum_id={} query='{}' parser_id={} код={}: {}",
                    r.getTaskId(), r.getStratumId(), r.getQuery(), r.getParserId(),
                    r.getErrorCode(), r.getError());
            return;
        }
        List<SearchResultMessage.Tile> tiles = r.getTiles() == null ? List.of() : r.getTiles();
        if (tiles.isEmpty()) {
            log.warn("Снимок выдачи без плиток: task_id={} stratum_id={} query='{}'",
                    r.getTaskId(), r.getStratumId(), r.getQuery());
            return;
        }

        OffsetDateTime parsedAt = parseTime(r.getParsedAt(), OffsetDateTime.now(ZoneOffset.UTC));
        OffsetDateTime runAt = parseTime(r.getRunAt(), parsedAt);
        List<SearchSnapshotRow> rows = new ArrayList<>(tiles.size());
        for (SearchResultMessage.Tile t : tiles) {
            if (t.getSku() == null || t.getPosition() == null) {
                continue;
            }
            rows.add(new SearchSnapshotRow(
                    runAt, parsedAt, nz(r.getMarketplace()), nz(r.getGeo()), nz(r.getCategory()),
                    id(r.getSetId()), id(r.getStratumId()), nz(r.getQuery()),
                    t.getPosition(), t.getSku(), nz(t.getName()), nz(t.getUrl()),
                    nz(t.getBrand()), Boolean.TRUE.equals(t.getBrandVerified()),
                    t.getPrice(), t.getOriginalPrice(), t.getDiscountPct(), t.getQuantity(),
                    t.getRating(), t.getReviews(),
                    t.getBadges() == null ? List.of() : t.getBadges(),
                    t.getLabels() == null ? List.of() : t.getLabels(),
                    nz(r.getParserId()), id(r.getTaskId())));
        }
        repository.insertTiles(rows);
        log.info("Снимок выдачи записан: run_at={} stratum_id={} query='{}' плиток={} parser_id={}",
                runAt, r.getStratumId(), r.getQuery(), rows.size(), r.getParserId());
    }

    private static OffsetDateTime parseTime(String text, OffsetDateTime fallback) {
        try {
            return text == null ? fallback : OffsetDateTime.parse(text);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static UUID id(UUID u) {
        return u == null ? NO_ID : u;
    }
}