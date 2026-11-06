package com.mercator.taskmanager.prod_specs;

import com.mercator.taskmanager.llm.LlmException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Разбор ответа модели на шаге 1: признаки карточки.
 *
 * Результат всегда содержит все поля схемы (пустые — null или пустой список) и extra.
 * Значения чистятся: нижний регистр, схлопнутые пробелы, без завершающей точки или запятой.
 * Ключ ответа, которого нет в схеме, не отбрасывается: он переносится в extra.
 * Нет JSON или нет ни одного признака — LlmException.
 */
public final class ProdSpecsCardParser {

    public static final String EXTRA = "extra";
    public static final List<String> SCALAR_FIELDS = List.of(
            "garment_type", "silhouette", "length", "sleeve", "neckline",
            "pockets", "fabric", "print", "style");
    public static final List<String> LIST_FIELDS = List.of("closure", "hardware", "decor", "colors");

    /** Признаков в extra не больше. */
    static final int MAX_EXTRA = 15;
    /** Значение признака extra, у которого модель его не указала. */
    static final String DEFAULT_EXTRA_VALUE = "есть";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Set<String> EMPTY_WORDS =
            Set.of("null", "none", "n/a", "нет данных", "не определено", "не указано", "-", "—");

    private ProdSpecsCardParser() {
    }

    /**
     * Признаки из ответа модели.
     *
     * @throws LlmException ответ не разобран или признаков нет
     */
    public static Map<String, Object> parse(String content) {
        String text = content == null ? "" : content.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new LlmException(LlmException.Kind.OTHER, "в ответе нет JSON: " + abbreviate(text));
        }
        Map<?, ?> json;
        try {
            json = MAPPER.readValue(text.substring(start, end + 1), Map.class);
        } catch (Exception e) {
            throw new LlmException(LlmException.Kind.OTHER, "JSON не разобран: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        boolean hasData = false;
        for (String field : SCALAR_FIELDS) {
            String value = scalar(json.get(field));
            out.put(field, value);
            hasData |= value != null;
        }
        for (String field : LIST_FIELDS) {
            List<String> values = list(json.get(field));
            out.put(field, values);
            hasData |= !values.isEmpty();
        }

        List<Map<String, String>> extra = new ArrayList<>(extraItems(json.get(EXTRA)));
        for (Map.Entry<?, ?> entry : json.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (SCALAR_FIELDS.contains(key) || LIST_FIELDS.contains(key) || EXTRA.equals(key)) {
                continue;
            }
            String name = normalize(key.replace('_', ' '));
            String value = scalar(entry.getValue());
            if (name != null && value != null) {
                extra.add(item(name, value));
            }
        }
        List<Map<String, String>> distinct = distinctByName(extra);
        out.put(EXTRA, distinct);
        hasData |= !distinct.isEmpty();

        if (!hasData) {
            throw new LlmException(LlmException.Kind.OTHER, "пустой набор признаков");
        }
        return out;
    }

    // ---------- Значения ----------

    /** Строка признака; список склеивается через запятую; пустое — null. */
    private static String scalar(Object value) {
        if (value instanceof List<?> items) {
            List<String> parts = new ArrayList<>();
            for (Object item : items) {
                String part = item instanceof List || item instanceof Map ? null : normalize(item);
                if (part != null) {
                    parts.add(part);
                }
            }
            return parts.isEmpty() ? null : String.join(", ", new LinkedHashSet<>(parts));
        }
        return value instanceof Map ? null : normalize(value);
    }

    /** Список признаков без повторов; строка делится по запятым. */
    private static List<String> list(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                String part = item instanceof List || item instanceof Map ? null : normalize(item);
                if (part != null) {
                    out.add(part);
                }
            }
        } else if (value instanceof String s) {
            for (String piece : s.split(",")) {
                String part = normalize(piece);
                if (part != null) {
                    out.add(part);
                }
            }
        }
        return new ArrayList<>(out);
    }

    /** extra: список пар {name, value}, список строк или словарь «название: значение». */
    private static List<Map<String, String>> extraItems(Object value) {
        List<Map<String, String>> out = new ArrayList<>();
        if (value instanceof List<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> map) {
                    String name = normalize(map.get("name"));
                    String itemValue = normalize(map.get("value"));
                    if (name != null) {
                        out.add(item(name, itemValue == null ? DEFAULT_EXTRA_VALUE : itemValue));
                    }
                } else {
                    String name = normalize(item);
                    if (name != null) {
                        out.add(item(name, DEFAULT_EXTRA_VALUE));
                    }
                }
            }
        } else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String name = normalize(entry.getKey());
                String itemValue = scalar(entry.getValue());
                if (name != null) {
                    out.add(item(name, itemValue == null ? DEFAULT_EXTRA_VALUE : itemValue));
                }
            }
        }
        return out;
    }

    private static List<Map<String, String>> distinctByName(List<Map<String, String>> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> item : items) {
            if (out.size() >= MAX_EXTRA) {
                break;
            }
            if (seen.add(item.get("name"))) {
                out.add(item);
            }
        }
        return out;
    }

    private static Map<String, String> item(String name, String value) {
        Map<String, String> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("value", value);
        return item;
    }

    /** Нижний регистр, схлопнутые пробелы, без завершающих знаков; пустое — null. */
    private static String normalize(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).replaceAll("\\s+", " ").strip()
                .toLowerCase(Locale.ROOT).replaceAll("[.,;:]+$", "").strip();
        return text.isEmpty() || EMPTY_WORDS.contains(text) ? null : text;
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}