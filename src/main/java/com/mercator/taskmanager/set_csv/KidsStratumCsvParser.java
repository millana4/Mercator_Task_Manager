package com.mercator.taskmanager.set_csv;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Разбирает иерархический CSV: строки подкатегорий чередуются со строками
 * предметов. У строки подкатегории заполнены левые колонки (надкатегория,
 * слой, категория, итог), у строки предмета — правые (предмет, запрос, доля).
 *
 * Количество карточек предмета не задано в файле напрямую: считается как
 * итог подкатегории × доля запроса с математическим округлением. Предметы,
 * которым при округлении достаётся ноль, отбрасываются — страта из нуля
 * карточек смысла не имеет.
 */
@Component
public class KidsStratumCsvParser {

    private static final Logger log = LoggerFactory.getLogger(KidsStratumCsvParser.class);

    private static final String COL_SEGMENT = "Надкатегория";
    private static final String COL_LAYER = "Слой";
    private static final String COL_SUBCATEGORY = "Категория";
    private static final String COL_TOTAL = "Итог подкатегории с округлением Озон";
    private static final String COL_ITEM = "Предмет";
    private static final String COL_QUERY = "Поисковый запрос";
    private static final String COL_SHARE = "Доля запроса";
    private static final String COL_SEASONAL = "Сезонный сплит";
    private static final String COL_BASE_SHARE = "Доля базы";

    public List<KidsStratumRow> parse(InputStream csvStream) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(csvStream, StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new IllegalArgumentException("CSV пустой: нет строки заголовка");
            }
            // BOM в начале файла, если есть, мешает распознать первую колонку
            headerLine = headerLine.replace("\uFEFF", "");

            String[] headers = splitCsvLine(headerLine);
            int idxSegment = indexOf(headers, COL_SEGMENT);
            int idxLayer = indexOf(headers, COL_LAYER);
            int idxSubcategory = indexOf(headers, COL_SUBCATEGORY);
            int idxTotal = indexOf(headers, COL_TOTAL);
            int idxItem = indexOf(headers, COL_ITEM);
            int idxQuery = indexOf(headers, COL_QUERY);
            int idxShare = indexOf(headers, COL_SHARE);
            int idxSeasonal = indexOf(headers, COL_SEASONAL);
            int idxBaseShare = indexOf(headers, COL_BASE_SHARE);

            List<KidsStratumRow> rows = new ArrayList<>();

            // Текущая подкатегория: её признаки применяются ко всем следующим
            // строкам предметов, пока не встретится новая строка подкатегории.
            String curTitle = null;
            String curSegment = null;
            Short curLayer = null;
            Integer curTotal = null;

            String line;
            int lineNumber = 1;

            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = splitCsvLine(line);

                String subcategory = get(cells, idxSubcategory).trim();
                String item = get(cells, idxItem).trim();

                if (!subcategory.isEmpty()) {
                    // Строка подкатегории
                    curTitle = subcategory;
                    curSegment = parseSegment(get(cells, idxSegment).trim(), lineNumber);
                    curLayer = parseLayer(get(cells, idxLayer).trim(), lineNumber);
                    curTotal = parseTotal(get(cells, idxTotal).trim(), lineNumber);
                    continue;
                }

                if (item.isEmpty()) {
                    continue;  // строка из одних запятых
                }

                // Строка предмета
                if (curTitle == null) {
                    throw new IllegalArgumentException(
                            "Строка " + lineNumber + ": предмет '" + item
                                    + "' идёт до первой строки подкатегории");
                }

                BigDecimal share = parseShare(get(cells, idxShare).trim());
                int count = BigDecimal.valueOf(curTotal)
                        .multiply(share)
                        .setScale(0, RoundingMode.HALF_UP)
                        .intValue();

                if (count == 0) {
                    log.warn("Строка {}: предмет '{}' ({} × {}) даёт 0 карточек — страта пропущена",
                            lineNumber, item, curTotal, share);
                    continue;
                }

                KidsStratumRow row = new KidsStratumRow();
                row.setSubcategoryTitle(curTitle);
                row.setSegment(curSegment);
                row.setLayer(curLayer);
                row.setItem(item);
                row.setQuery(get(cells, idxQuery).trim());
                row.setCount(count);
                row.setIsSeasonal(parseBoolean(get(cells, idxSeasonal).trim()));
                row.setBaseShare(parseBaseShare(get(cells, idxBaseShare).trim()));
                rows.add(row);
            }

            if (rows.isEmpty()) {
                throw new IllegalArgumentException("В CSV не нашлось ни одной страты");
            }
            return rows;

        } catch (IOException e) {
            throw new IllegalArgumentException("Не удалось прочитать CSV: " + e.getMessage(), e);
        }
    }

    // Female -> f, Male -> m, Baby -> baby
    private String parseSegment(String raw, int line) {
        return switch (raw.toLowerCase()) {
            case "female", "ж" -> "f";
            case "male", "м" -> "m";
            case "baby" -> "baby";
            default -> throw new IllegalArgumentException(
                    "Строка " + line + ": непонятная надкатегория '" + raw
                            + "' (ожидалось Female, Male или Baby)");
        };
    }

    private Short parseLayer(String raw, int line) {
        for (char c : raw.toCharArray()) {
            if (Character.isDigit(c)) {
                return (short) Character.getNumericValue(c);
            }
        }
        throw new IllegalArgumentException(
                "Строка " + line + ": не нашёл номер слоя в '" + raw + "'");
    }

    private Integer parseTotal(String raw, int line) {
        try {
            return new BigDecimal(raw).setScale(0, RoundingMode.HALF_UP).intValue();
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Строка " + line + ": итог подкатегории '" + raw + "' не число");
        }
    }

    // Пустая доля означает единственный предмет в подкатегории — вся доля его.
    private BigDecimal parseShare(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ONE;
        }
        return new BigDecimal(raw);
    }

    private Boolean parseBoolean(String raw) {
        return "TRUE".equalsIgnoreCase(raw);
    }

    private BigDecimal parseBaseShare(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return new BigDecimal(raw);
    }

    private int indexOf(String[] headers, String name) {
        for (int i = 0; i < headers.length; i++) {
            if (headers[i].trim().equalsIgnoreCase(name)) {
                return i;
            }
        }
        throw new IllegalArgumentException("В CSV нет колонки '" + name + "'");
    }

    private String get(String[] cells, int idx) {
        return idx < cells.length ? cells[idx] : "";
    }

    private String[] splitCsvLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        result.add(current.toString());
        return result.toArray(new String[0]);
    }
}