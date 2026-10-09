package com.mercator.taskmanager;

import com.mercator.taskmanager.embedding.EmbedderClient;
import com.mercator.taskmanager.matching.CardFeatures;
import com.mercator.taskmanager.matching.CardFeatures.ModelSize;
import com.mercator.taskmanager.matching.DuplicateFilter;
import com.mercator.taskmanager.matching.NicheMatchingConfig;
import com.mercator.taskmanager.matching.NicheScorer;
import com.mercator.taskmanager.matching.NicheScorer.Candidate;
import com.mercator.taskmanager.matching.NicheScorer.Scored;
import com.mercator.taskmanager.matching.SizeScale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Схожесть кандидатов с референсом и отсев задвоений; эмбеддер подменён. */
class NicheScorerTest {

    private static final Set<String> GIRLS = Set.of("девочки");
    private static final Set<String> BOYS = Set.of("мальчики");
    private static final Set<String> BOTH = Set.of("девочки", "мальчики");

    private final NicheMatchingConfig config = MatchingConfigLoader.load();
    private EmbedderClient embedder;
    private NicheScorer scorer;

    @BeforeEach
    void setUp() {
        embedder = mock(EmbedderClient.class);
        // Все тексты — один и тот же вектор: косинус 1, текстовая близость 1
        when(embedder.embed(anyList())).thenAnswer(inv -> {
            List<String> texts = inv.getArgument(0);
            return texts.stream().map(t -> new float[]{1f, 0f, 0f}).toList();
        });
        scorer = new NicheScorer(config, embedder);
    }

    @Test
    void hardFilterByTypeAndGender() {
        CardFeatures ref = card("ref", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 134.0, 1000.0);
        List<Scored> result = scorer.score(ref, List.of(
                candidate(card("jeans", "Джинсы", GIRLS, Map.of("Материал", "хлопок"), 134.0, 1000.0)),
                candidate(card("boys", "Брюки", BOYS, Map.of("Материал", "хлопок"), 134.0, 1000.0)),
                candidate(card("both", "Брюки", BOTH, Map.of("Материал", "хлопок"), 134.0, 1000.0)),
                candidate(card("unknown", "Брюки", Set.of(), Map.of("Материал", "хлопок"), 134.0, 1000.0))));

        Map<String, String> reasons = new LinkedHashMap<>();
        result.forEach(s -> reasons.put(s.features().sku(), s.rejectReason()));
        assertEquals(NicheScorer.REJECT_TYPE, reasons.get("jeans"));
        assertEquals(NicheScorer.REJECT_GENDER, reasons.get("boys"));
        assertNull(reasons.get("both"));
        assertNull(reasons.get("unknown"));
        // прошедшие фильтр — впереди
        assertEquals(Set.of("both", "unknown"),
                Set.of(result.get(0).features().sku(), result.get(1).features().sku()));
    }

    @Test
    void textOnReferenceKeysAndStoredVectorReused() {
        CardFeatures ref = card("ref", "Брюки", GIRLS, Map.of("Материал", "хлопок", "Цвет", "синий"), 134.0, 1000.0);
        CardFeatures fresh = card("fresh", "Брюки", GIRLS,
                Map.of("Материал", "хлопок", "Цвет", "красный", "Модель", "джоггеры"), 134.0, 1000.0);
        CardFeatures stored = card("stored", "Брюки", GIRLS, Map.of("Материал", "лён"), 134.0, 1000.0);

        List<Scored> result = scorer.score(ref, List.of(
                candidate(fresh),
                new Candidate(UUID.randomUUID(), stored, "Материал: лён", new float[]{1f, 0f, 0f})));

        // «Модели» у референса нет — в текст кандидата она не идёт
        Scored freshScored = result.stream().filter(s -> s.features().sku().equals("fresh")).findFirst().orElseThrow();
        assertEquals("Материал: хлопок; Цвет: красный", freshScored.text());
        // текст stored не изменился — его вектор не пересчитывается
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> sent = ArgumentCaptor.forClass(List.class);
        verify(embedder).embed(sent.capture());
        assertEquals(List.of("Материал: хлопок; Цвет: синий", "Материал: хлопок; Цвет: красный"), sent.getValue());
    }

    @Test
    void neededSizeOfModelIsSubstituted() {
        CardFeatures ref = card("ref", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 134.0, 1000.0);
        CardFeatures cand = card("c116", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 116.0, 900.0,
                new ModelSize("c128", 128, 128, 950.0, "128 RU"),
                new ModelSize("c134", 134, 134, 1000.0, "134 RU"));
        CardFeatures noSize = card("c98", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 98.0, 1000.0,
                new ModelSize("c104", 104, 104, 1000.0, "104 RU"));

        List<Scored> result = scorer.score(ref, List.of(candidate(cand), candidate(noSize)));

        Scored substituted = result.stream().filter(s -> s.features().sku().equals("c116")).findFirst().orElseThrow();
        assertEquals("c134", substituted.analogSku());
        assertEquals("134 RU", substituted.sizeLabel());
        assertEquals(1000.0, substituted.analogUnitPrice());
        assertEquals(1.0, substituted.parts().size());
        assertEquals(1.0, substituted.parts().price(), 1e-9);

        Scored kept = result.stream().filter(s -> s.features().sku().equals("c98")).findFirst().orElseThrow();
        assertEquals("c98", kept.analogSku());
        assertNull(kept.sizeLabel());
        assertEquals(0.0, kept.parts().size());
    }

    @Test
    void weightsRedistributedWhenTemperatureMissing() {
        CardFeatures ref = card("ref", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 134.0, 1000.0);
        // цена вдвое выше — близость цены 0; текст 1, размер 1, натуральные 1, температуры нет
        CardFeatures cand = card("c", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 134.0, 2000.0);

        Scored s = scorer.score(ref, List.of(candidate(cand))).get(0);

        assertEquals(1.0, s.parts().text(), 1e-9);
        assertEquals(0.0, s.parts().price(), 1e-9);
        assertNull(s.parts().temperature());
        assertEquals((0.55 + 0.10 + 0.10) / 0.90, s.score(), 1e-9);
    }

    @Test
    void duplicateFilter() {
        CardFeatures ref = card("ref", "Брюки", GIRLS, Map.of("Материал", "хлопок"), 134.0, 1000.0);
        DuplicateFilter filter = new DuplicateFilter(config, ref);

        CardFeatures first = card("a", "Брюки", GIRLS, Map.of("Материал", "футер", "Цвет", "синий"), 134.0, 1000.0);
        assertNull(filter.rejectReason(first));
        filter.take(first);

        // другой цвет, цена на 3% выше — та же модель без связи переключателем
        CardFeatures otherColor = card("b", "Брюки", GIRLS, Map.of("Материал", "футер", "Цвет", "серый"), 134.0, 1030.0);
        assertEquals(DuplicateFilter.REJECT_TEXT_DUPLICATE, filter.rejectReason(otherColor));
        // цена на 6% выше — не дубль
        CardFeatures pricier = card("c", "Брюки", GIRLS, Map.of("Материал", "футер", "Цвет", "серый"), 134.0, 1064.0);
        assertNull(filter.rejectReason(pricier));
        // sku из переключателя взятой карточки — та же модель
        CardFeatures sameModel = card("a-140", "Брюки", GIRLS, Map.of("Материал", "вельвет"), 140.0, 1000.0);
        CardFeatures sameModelLinked = withModelSkus(sameModel, Set.of("a-140", "a"));
        assertEquals(DuplicateFilter.REJECT_SAME_MODEL, filter.rejectReason(sameModelLinked));
    }

    // ---------- Вспомогательные ----------

    private static Candidate candidate(CardFeatures features) {
        return new Candidate(UUID.randomUUID(), features, null, null);
    }

    private CardFeatures card(String sku, String group, Set<String> genders, Map<String, String> values,
                              Double size, Double unitPrice, ModelSize... sizes) {
        Map<String, String> featureValues = new LinkedHashMap<>();
        for (String key : config.featuresOf(group)) {
            featureValues.put(key, values.getOrDefault(key, ""));
        }
        Set<String> model = new LinkedHashSet<>();
        model.add(sku);
        for (ModelSize s : sizes) {
            model.add(s.sku());
        }
        return new CardFeatures(sku, sku, group, genders, featureValues, SizeScale.HEIGHT, size, size,
                null, null, 100.0, 1, unitPrice, model, List.of(sizes));
    }

    private static CardFeatures withModelSkus(CardFeatures f, Set<String> modelSkus) {
        return new CardFeatures(f.sku(), f.name(), f.group(), f.genders(), f.featureValues(), f.sizeScale(),
                f.sizeMin(), f.sizeMax(), f.tempMin(), f.tempMax(), f.naturalShare(), f.packCount(),
                f.unitPrice(), modelSkus, f.modelSizes());
    }
}