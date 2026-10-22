package com.mercator.taskmanager.clickhouse;

import com.mercator.taskmanager.redis.PrioritySku;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Запись снимков выдачи в ClickHouse. Все плитки одного результата - одним
 * INSERT (в ClickHouse каждая вставка создаёт часть, мелкие вставки вредны).
 * Массивы бейджей и меток подставляются литералами, как Map в CardSnapshotRepository.
 * Чтение: частота SKU в выдаче страты для приоритета в задаче подбора.
 */
@Repository
public class SearchSnapshotRepository {

    private static final String COLUMNS = "run_at, parsed_at, marketplace, geo, category, set_id, "
            + "stratum_id, query, position, sku, name, url, brand, brand_verified, price, "
            + "original_price, discount_pct, quantity, rating, reviews, badges, labels, parser_id, task_id";

    private final JdbcTemplate clickhouse;

    public SearchSnapshotRepository(@Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate) {
        this.clickhouse = clickhouseJdbcTemplate;
    }

    public void insertTiles(List<SearchSnapshotRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder("INSERT INTO search_snapshot (" + COLUMNS + ") VALUES ");
        List<Object> args = new ArrayList<>(rows.size() * 22);
        for (int i = 0; i < rows.size(); i++) {
            SearchSnapshotRow r = rows.get(i);
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ")
                    .append(arraySql(r.badges())).append(", ").append(arraySql(r.labels()))
                    .append(", ?, ?)");
            args.addAll(Arrays.asList(
                    ts(r.runAt()), ts(r.parsedAt()), r.marketplace(), r.geo(), r.category(),
                    r.setId(), r.stratumId(), r.query(), r.position(), r.sku(), r.name(), r.url(),
                    r.brand(), r.brandVerified() ? 1 : 0, r.price(), r.originalPrice(),
                    r.discountPct(), r.quantity(), r.rating(), r.reviews()));
            args.add(r.parserId());
            args.add(r.taskId());
        }
        clickhouse.update(sql.toString(), args.toArray());
    }

    /**
     * Частота SKU в выдаче страты за последние days суток: в скольких прогонах
     * (разных run_at) встретился SKU. По убыванию частоты, при равенстве — по sku.
     * days — целое из конфигурации, подставляется литералом.
     */
    public List<PrioritySku> findSkuFrequency(UUID stratumId, int days) {
        return clickhouse.query(
                "SELECT sku, uniqExact(run_at) AS runs FROM search_snapshot "
                        + "WHERE stratum_id = ? AND run_at > now() - INTERVAL " + days + " DAY "
                        + "AND sku != '' GROUP BY sku ORDER BY runs DESC, sku",
                (rs, rowNum) -> new PrioritySku(rs.getString("sku"), rs.getLong("runs")),
                stratumId);
    }

    private static Timestamp ts(OffsetDateTime t) {
        return Timestamp.from(t.toInstant().truncatedTo(ChronoUnit.SECONDS));
    }

    /** ['a', 'b'] с экранированием обратной косой черты и кавычки. */
    static String arraySql(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
                .map(v -> "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'")
                .collect(Collectors.joining(", ", "[", "]"));
    }
}