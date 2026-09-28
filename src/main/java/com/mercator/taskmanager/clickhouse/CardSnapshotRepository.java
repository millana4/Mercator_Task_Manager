package com.mercator.taskmanager.clickhouse;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Доступ к снимкам карточек в ClickHouse (таблица card_snapshot).
 * Пишем прямым SQL через clickhouseJdbcTemplate.
 *
 * Тонкости ClickHouse:
 *  - Map(String,String) для характеристик передаём как готовую SQL-строку map(...);
 *  - Array(Float32) для вектора — как массив;
 *  - variants уже приходит JSON-строкой.
 */
@Repository
public class CardSnapshotRepository {

    private final JdbcTemplate clickhouse;

    public CardSnapshotRepository(
            @Qualifier("clickhouseJdbcTemplate") JdbcTemplate clickhouseJdbcTemplate) {
        this.clickhouse = clickhouseJdbcTemplate;
    }

    /** Поля последнего снимка карточки, нужные для текста эмбеддинга. */
    public record SnapshotText(String name, String categoryPath,
                               String characteristics, String description) {
    }

    public void insert(CardSnapshot s) {
        // Характеристики и вектор ClickHouse удобнее принять через функции
        // map(...) и array(...), которые мы соберём строкой и подставим.
        String characteristicsSql = buildMapSql(s.getCharacteristics());
        String embeddingSql = buildArraySql(s.getEmbedding());

        String sql =
                "INSERT INTO card_snapshot " +
                        "(card_id, sku, parsed_at, name, description, brand, category, category_path, " +
                        " characteristics, variants_aspect, variants, " +
                        " seller_id, seller_name, seller_legal_name, seller_ogrn, embedding) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, " + characteristicsSql + ", ?, ?, ?, ?, ?, ?, " + embeddingSql + ")";

        clickhouse.update(sql,
                s.getCardId(),
                s.getSku(),
                Timestamp.from(s.getParsedAt().toInstant().truncatedTo(ChronoUnit.SECONDS)),
                nvl(s.getName()),
                nvl(s.getDescription()),
                nvl(s.getBrand()),
                nvl(s.getCategory()),
                nvl(s.getCategoryPath()),
                nvl(s.getVariantsAspect()),
                nvl(s.getVariants()),
                nvl(s.getSellerId()),
                nvl(s.getSellerName()),
                nvl(s.getSellerLegalName()),
                nvl(s.getSellerOgrn())
        );
    }

    /** Последний снимок карточки (FINAL — чтобы ReplacingMergeTree отдал актуальную версию). */
    public long countByCardId(java.util.UUID cardId) {
        Long count = clickhouse.queryForObject(
                "SELECT count() FROM card_snapshot FINAL WHERE card_id = ?",
                Long.class, cardId);
        return count != null ? count : 0L;
    }

    /**
     * Текстовые поля последнего снимка по каждой карточке — для эмбеддингов.
     * Последняя версия — argMax по parsed_at (не зависит от того, схлопнул ли
     * ReplacingMergeTree версии). Характеристики склеиваются в строку в SQL:
     * до 15 пар «ключ: значение» через «; ».
     */
    public Map<UUID, SnapshotText> findTextsByCardIds(Collection<UUID> cardIds) {
        Map<UUID, SnapshotText> result = new HashMap<>();
        if (cardIds == null || cardIds.isEmpty()) {
            return result;
        }
        String placeholders = String.join(", ", Collections.nCopies(cardIds.size(), "?"));
        String sql =
                "SELECT card_id, " +
                        "argMax(name, parsed_at) AS name, " +
                        "argMax(category_path, parsed_at) AS category_path, " +
                        "argMax(description, parsed_at) AS description, " +
                        "argMax(arrayStringConcat(arraySlice(arrayMap((k, v) -> concat(k, ': ', v), " +
                        "mapKeys(characteristics), mapValues(characteristics)), 1, 15), '; '), " +
                        "parsed_at) AS chars " +
                        "FROM card_snapshot " +
                        "WHERE card_id IN (" + placeholders + ") " +
                        "GROUP BY card_id";

        clickhouse.query(sql, (RowCallbackHandler) rs -> result.put(
                        UUID.fromString(rs.getString("card_id")),
                        new SnapshotText(
                                rs.getString("name"),
                                rs.getString("category_path"),
                                rs.getString("chars"),
                                rs.getString("description"))),
                cardIds.toArray());
        return result;
    }

    // --- вспомогательное ---

    // ClickHouse String не любит null — заменяем на пустую строку.
    private String nvl(String v) {
        return v != null ? v : "";
    }

    // Собираем литерал map('k1','v1','k2','v2', ...) из Map.
    // Экранируем одинарные кавычки в ключах/значениях.
    private String buildMapSql(Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return "map()";
        }
        StringBuilder sb = new StringBuilder("map(");
        boolean first = true;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (!first) sb.append(", ");
            sb.append('\'').append(esc(e.getKey())).append('\'')
                    .append(", ")
                    .append('\'').append(esc(e.getValue())).append('\'');
            first = false;
        }
        sb.append(')');
        return sb.toString();
    }

    // Собираем литерал [f1, f2, ...] из списка Float.
    private String buildArraySql(List<Float> vec) {
        if (vec == null || vec.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(vec.get(i));
        }
        sb.append(']');
        return sb.toString();
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("'", "\\'");
    }
}