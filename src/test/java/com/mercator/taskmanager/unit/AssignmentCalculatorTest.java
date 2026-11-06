package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.assignment.AssignmentCalculator;
import com.mercator.taskmanager.assignment.AssignmentCalculator.Params;
import com.mercator.taskmanager.assignment.AssignmentCalculator.SubcategoryFigures;
import com.mercator.taskmanager.assignment.SeasonType;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Формулы задания против реальных CSV детской одежды: каждая расчётная колонка
 * строки подкатегории совпадает с таблицей, суммы количеств и квот — с импортом
 * и аналитикой (Spring Summer 2026: 624 / 205, Autumn Winter 2027: 632 / 201).
 */
class AssignmentCalculatorTest {

    private static final Params PARAMS = new Params(200, 30, new BigDecimal("1.35"));
    private static final BigDecimal TOLERANCE = new BigDecimal("1e-6");

    /** Итоги проверки одного файла. */
    private record Totals(int subcategories, int strata, int planSum, int quotaSum, int zeroStrata) {
    }

    @Test
    void springSummer2026MatchesCsv() {
        Totals t = checkCsv("/csv/represent_set_kids_clothes_ss26.csv");
        assertEquals(41, t.subcategories());
        assertEquals(58, t.strata());
        assertEquals(624, t.planSum());
        assertEquals(205, t.quotaSum());
        assertEquals(1, t.zeroStrata(), "Бюстгальтер: 2 × 0,2 = 0,4 → 0");
    }

    @Test
    void autumnWinter2027MatchesCsv() {
        Totals t = checkCsv("/csv/represent_set_kids_clothes_aw27.csv");
        assertEquals(41, t.subcategories());
        assertEquals(58, t.strata());
        assertEquals(632, t.planSum());
        assertEquals(201, t.quotaSum());
        assertEquals(1, t.zeroStrata());
    }

    @Test
    void trackedSubcategoryIsRaisedToMinimum() {
        SubcategoryFigures f = AssignmentCalculator.subcategory(new BigDecimal("0.01"), true, PARAMS);
        assertEquals(0, f.base().compareTo(new BigDecimal("2")));
        assertEquals(0, f.withAddition().compareTo(new BigDecimal("30")));
        assertEquals(41, f.total(), "30 × 1,35 = 40,5 → 41");
    }

    @Test
    void trackedSubcategoryAboveMinimumKeepsBase() {
        SubcategoryFigures f = AssignmentCalculator.subcategory(new BigDecimal("0.2"), true, PARAMS);
        assertEquals(0, f.withAddition().compareTo(new BigDecimal("40")));
        assertEquals(54, f.total());
    }

    @Test
    void emptyQueryShareMeansWholeSubcategory() {
        assertEquals(41, AssignmentCalculator.stratumCount(41, null));
        assertEquals(3, AssignmentCalculator.analysisQuota(new BigDecimal("0.015"), null, PARAMS));
    }

    @Test
    void halfRoundsUp() {
        assertEquals(3, AssignmentCalculator.stratumCount(5, new BigDecimal("0.5")));
        assertEquals(0, AssignmentCalculator.stratumCount(2, new BigDecimal("0.2")));
    }

    @Test
    void seasonTitle() {
        assertEquals("Autumn Winter 2027", SeasonType.title(SeasonType.AUTUMN_WINTER, (short) 2027));
        assertEquals("Spring Summer 2026", SeasonType.title(SeasonType.SPRING_SUMMER, (short) 2026));
        assertNull(SeasonType.title(null, null));
    }

    @Test
    void nextSeason() {
        assertEquals(SeasonType.AUTUMN_WINTER, SeasonType.nextType(SeasonType.SPRING_SUMMER));
        assertEquals((short) 2027, SeasonType.nextYear(SeasonType.SPRING_SUMMER, (short) 2026));
        assertEquals(SeasonType.SPRING_SUMMER, SeasonType.nextType(SeasonType.AUTUMN_WINTER));
        assertEquals((short) 2027, SeasonType.nextYear(SeasonType.AUTUMN_WINTER, (short) 2027));
    }

    // ---------- Разбор CSV и построчная сверка ----------

    private Totals checkCsv(String resource) {
        List<String[]> rows = new ArrayList<>();
        Map<String, Integer> idx = new HashMap<>();
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "Нет файла " + resource);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String[] header = split(reader.readLine().replace("\uFEFF", ""));
            for (int i = 0; i < header.length; i++) {
                idx.put(header[i].trim(), i);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    rows.add(split(line));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Не прочитать " + resource, e);
        }

        int subcategories = 0, strata = 0, planSum = 0, quotaSum = 0, zero = 0;
        BigDecimal share = null;
        int total = 0;
        for (String[] r : rows) {
            String subcategory = cell(r, idx, "Категория");
            String item = cell(r, idx, "Предмет");
            if (!subcategory.isEmpty()) {
                share = new BigDecimal(cell(r, idx, "Доля категории Озон"));
                boolean tracked = "TRUE".equalsIgnoreCase(cell(r, idx, "Отслеживать подкатегорию"));
                SubcategoryFigures f = AssignmentCalculator.subcategory(share, tracked, PARAMS);
                assertClose(cell(r, idx, "Необх. кол-ко карточек Озон для анализа кат."), f.base(), subcategory);
                assertClose(cell(r, idx, "С добавкой на анализ подкатегории Озон"), f.withAddition(), subcategory);
                assertClose(cell(r, idx, "С поправкой на ротацию Озон"), f.withRotation(), subcategory);
                assertEquals(Integer.parseInt(cell(r, idx, "Итог подкатегории с округлением Озон")),
                        f.total(), "Итог подкатегории " + subcategory);
                total = f.total();
                subcategories++;
            } else if (!item.isEmpty()) {
                String raw = cell(r, idx, "Доля запроса");
                BigDecimal queryShare = raw.isEmpty() ? null : new BigDecimal(raw);
                int count = AssignmentCalculator.stratumCount(total, queryShare);
                planSum += count;
                quotaSum += AssignmentCalculator.analysisQuota(share, queryShare, PARAMS);
                if (count == 0) {
                    zero++;
                }
                strata++;
            }
        }
        return new Totals(subcategories, strata, planSum, quotaSum, zero);
    }

    private static void assertClose(String expected, BigDecimal actual, String subcategory) {
        BigDecimal diff = new BigDecimal(expected).subtract(actual).abs();
        assertTrue(diff.compareTo(TOLERANCE) <= 0,
                "Подкатегория " + subcategory + ": ожидалось " + expected + ", получено " + actual);
    }

    private static String cell(String[] row, Map<String, Integer> idx, String column) {
        Integer i = idx.get(column);
        assertNotNull(i, "В CSV нет колонки " + column);
        return i < row.length ? row[i].trim() : "";
    }

    // Значения в кавычках («Брюки, джинсы и шорты») могут содержать запятые.
    private static String[] split(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (char c : line.toCharArray()) {
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out.toArray(new String[0]);
    }
}