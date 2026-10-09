package com.mercator.taskmanager;

import com.mercator.taskmanager.matching.CardFeatureExtractor;
import com.mercator.taskmanager.matching.CardFeatures;
import com.mercator.taskmanager.matching.CardFeatures.ModelSize;
import com.mercator.taskmanager.matching.SizeScale;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Разбор признаков карточки на Java совпадает с ноутбуком разбора признаков.
 * Эталоны — niche_matching_golden.jsonl (ячейка 25 ноутбука): 621 карточка детской
 * одежды, по каждой — вход (card) и результат ноутбука (expected).
 */
class CardFeatureExtractorGoldenTest {

    private static final String GOLDEN = "/niche/niche_matching_golden.jsonl";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final CardFeatureExtractor extractor = new CardFeatureExtractor(MatchingConfigLoader.load());

    @Test
    @SuppressWarnings("unchecked")
    void allCardsMatchNotebook() throws Exception {
        List<String> failures = new ArrayList<>();
        int cards = 0;
        try (InputStream in = Objects.requireNonNull(getClass().getResourceAsStream(GOLDEN), GOLDEN);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                cards++;
                Map<String, Object> row = MAPPER.readValue(line, Map.class);
                Map<String, Object> card = (Map<String, Object>) row.get("card");
                Map<String, Object> expected = (Map<String, Object>) row.get("expected");
                for (String problem : compare(extractor.extract(card), expected)) {
                    failures.add(card.get("sku") + ": " + problem);
                }
            }
        }

        assertEquals(621, cards);
        assertTrue(failures.isEmpty(), failures.size() + " расхождений с ноутбуком, первые:\n"
                + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
    }

    @SuppressWarnings("unchecked")
    private static List<String> compare(CardFeatures f, Map<String, Object> e) {
        List<String> bad = new ArrayList<>();
        check(bad, "группа", e.get("group"), f.group());
        check(bad, "пол", new TreeSet<>((List<String>) e.get("genders")), new TreeSet<>(f.genders()));
        check(bad, "текст", e.get("text"), f.text());
        check(bad, "шкала", e.get("size_scale"), f.sizeScale() == SizeScale.FOOT ? "стопа, см" : "рост, см");
        checkNumber(bad, "размер мин", e.get("size_min"), f.sizeMin(), 1e-9);
        checkNumber(bad, "размер макс", e.get("size_max"), f.sizeMax(), 1e-9);
        checkNumber(bad, "темп мин", e.get("temp_min"), f.tempMin(), 1e-9);
        checkNumber(bad, "темп макс", e.get("temp_max"), f.tempMax(), 1e-9);
        // доля округлена до 0,1 — допуск на разное округление половин в Python и Java
        checkNumber(bad, "натуральные", e.get("natural_share"), f.naturalShare(), 0.051);
        check(bad, "в упаковке", ((Number) e.get("pack_count")).intValue(), f.packCount());
        checkNumber(bad, "цена за шт", e.get("unit_price"), f.unitPrice(), 1e-6);
        check(bad, "sku модели", new TreeSet<>((List<String>) e.get("model_skus")), new TreeSet<>(f.modelSkus()));

        List<Map<String, Object>> sizes = (List<Map<String, Object>>) e.get("model_sizes");
        if (sizes.size() != f.modelSizes().size()) {
            bad.add("размеры модели: " + sizes.size() + " / " + f.modelSizes().size());
        } else {
            for (int i = 0; i < sizes.size(); i++) {
                Map<String, Object> s = sizes.get(i);
                ModelSize m = f.modelSizes().get(i);
                check(bad, "размер модели " + i + " sku", s.get("sku"), m.sku());
                checkNumber(bad, "размер модели " + i + " мин", s.get("min"), m.min(), 1e-9);
                checkNumber(bad, "размер модели " + i + " макс", s.get("max"), m.max(), 1e-9);
                checkNumber(bad, "размер модели " + i + " цена", s.get("unit_price"), m.unitPrice(), 1e-6);
                check(bad, "размер модели " + i + " подпись", s.get("label"), m.label());
            }
        }
        return bad;
    }

    private static void check(List<String> bad, String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            bad.add(what + ": ноутбук «" + expected + "», ядро «" + actual + "»");
        }
    }

    private static void checkNumber(List<String> bad, String what, Object expected, Double actual, double tolerance) {
        Double exp = expected == null ? null : ((Number) expected).doubleValue();
        boolean same = exp == null || actual == null ? exp == actual : Math.abs(exp - actual) <= tolerance;
        if (!same) {
            bad.add(what + ": ноутбук " + exp + ", ядро " + actual);
        }
    }
}