package com.mercator.taskmanager.embedding;

import com.mercator.taskmanager.clickhouse.CardSnapshotRepository.SnapshotText;
import com.mercator.taskmanager.entity.NicheEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Текст карточки для эмбеддинга — одинаковый для референса и кандидатов:
 * название; раздел маркетплейса; до 15 характеристик; начало описания.
 * Бренд не включается: иначе близость тянула бы к товарам того же бренда,
 * а не к похожим по свойствам.
 */
public final class EmbeddingTextBuilder {

    private static final int MAX_CHARACTERISTICS = 15;
    private static final int MAX_DESCRIPTION = 300;

    private EmbeddingTextBuilder() {
    }

    /** Из данных карточки от парсера (snake_case-карта OzonCard). */
    public static String fromSnapshot(Map<String, Object> s) {
        if (s == null) {
            return "";
        }
        String characteristics = null;
        if (s.get("characteristics") instanceof Map<?, ?> map && !map.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (parts.size() >= MAX_CHARACTERISTICS) {
                    break;
                }
                parts.add(e.getKey() + ": " + e.getValue());
            }
            characteristics = String.join("; ", parts);
        }
        return build(str(s.get("name")), str(s.get("category_path")),
                characteristics, str(s.get("description")));
    }

    /** Из последнего снимка карточки в ClickHouse. */
    public static String fromClickhouse(SnapshotText t) {
        if (t == null) {
            return "";
        }
        return build(t.name(), t.categoryPath(), t.characteristics(), t.description());
    }

    /** Референс ниши: его карточка, а без неё — описание пользователя и поисковый запрос. */
    public static String forReference(NicheEntity niche) {
        String fromCard = fromSnapshot(niche.getReferenceSnapshot());
        if (!fromCard.isBlank()) {
            return fromCard;
        }
        List<String> parts = new ArrayList<>();
        if (niche.getDescription() != null && !niche.getDescription().isBlank()) {
            parts.add(niche.getDescription().strip());
        }
        if (niche.getSearchQuery() != null && !niche.getSearchQuery().isBlank()) {
            parts.add(niche.getSearchQuery().strip());
        }
        return String.join(". ", parts);
    }

    private static String build(String name, String categoryPath,
                                String characteristics, String description) {
        List<String> parts = new ArrayList<>();
        if (notBlank(name)) {
            parts.add(name.strip());
        }
        if (notBlank(categoryPath)) {
            parts.add("Раздел: " + categoryPath.strip());
        }
        if (notBlank(characteristics)) {
            parts.add("Характеристики: " + characteristics.strip());
        }
        if (notBlank(description)) {
            String d = description.replaceAll("\\s+", " ").strip();
            parts.add("Описание: " + (d.length() <= MAX_DESCRIPTION ? d : d.substring(0, MAX_DESCRIPTION)));
        }
        return String.join(". ", parts);
    }

    private static String str(Object value) {
        return value instanceof String s ? s : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}