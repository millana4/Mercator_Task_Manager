package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.set_csv.StratumCsvParser;
import com.mercator.taskmanager.set_csv.StratumRow;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Тест разбора CSV на реальном файле (src/test/resources/csv/set_sample.csv).
 * Не привязан к точному числу строк — проверяет, что все содержательные
 * строки прочитаны и что тонкие места (гендер, слой, доля базы, сезонность)
 * разобраны правильно. Файл может быть любой длины.
 */
class StratumCsvParserTest {

    private final StratumCsvParser parser = new StratumCsvParser();

    @Test
    void parsesRealFile() {
        InputStream csv = getClass().getResourceAsStream("/csv/set_sample.csv");
        assertNotNull(csv, "Файл /csv/set_sample.csv не найден в тестовых ресурсах");

        List<StratumRow> rows = parser.parse(csv);

        // Файл не пустой — хотя бы одна страта разобрана.
        assertFalse(rows.isEmpty(), "Ожидали хотя бы одну распарсенную строку");

        // Первая строка (Трусы женские) — по твоим данным.
        StratumRow first = rows.get(0);
        assertEquals("Трусы", first.getItem());
        assertEquals("Трусы женские", first.getQuery());
        assertEquals("f", first.getGender());
        assertEquals((short) 0, first.getLayer());
        assertEquals(4, first.getCount());
        assertFalse(first.getIsSeasonal());
        assertNull(first.getBaseShare());

        // Инварианты, которые должны держаться для КАЖДОЙ строки, сколько бы их ни было:

        // Предмет и запрос всегда заполнены (пустые строки отброшены парсером).
        assertTrue(rows.stream().allMatch(r ->
                        r.getItem() != null && !r.getItem().isBlank()
                                && r.getQuery() != null && !r.getQuery().isBlank()),
                "У каждой страты должны быть предмет и запрос");

        // Слой всегда 0-3.
        assertTrue(rows.stream().allMatch(r -> r.getLayer() >= 0 && r.getLayer() <= 3),
                "Все слои должны быть 0-3");

        // Гендер всегда f или m.
        assertTrue(rows.stream().allMatch(r ->
                        "f".equals(r.getGender()) || "m".equals(r.getGender())),
                "Все гендеры должны быть f или m");

        // Количество всегда положительное.
        assertTrue(rows.stream().allMatch(r -> r.getCount() != null && r.getCount() > 0),
                "Количество должно быть положительным");

        // Ключевой инвариант: доля базы задана только у сезонных страт.
        assertTrue(rows.stream()
                        .filter(r -> r.getBaseShare() != null)
                        .allMatch(r -> Boolean.TRUE.equals(r.getIsSeasonal())),
                "Доля базы должна быть только у сезонных страт");
    }
}