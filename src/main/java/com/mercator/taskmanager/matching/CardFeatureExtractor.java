package com.mercator.taskmanager.matching;

import com.mercator.taskmanager.matching.CardFeatures.ModelSize;
import com.mercator.taskmanager.matching.SizeNormalizer.SizeRange;
import com.mercator.taskmanager.matching.TemperatureNormalizer.TemperatureRange;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.UNICODE_CASE;
import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Признаки карточки для подбора аналогов — из полного набора карточки от парсера
 * (card.content_txt, niche.reference_snapshot): карта с ключами в snake_case.
 *
 * Перенос ячейки 19 ноутбука разбора признаков; проверяется эталонами niche_matching_golden.jsonl.
 */
@Component
public class CardFeatureExtractor {

    public static final String GIRLS = "девочки";
    public static final String BOYS = "мальчики";

    private static final int FLAGS = CASE_INSENSITIVE | UNICODE_CASE | UNICODE_CHARACTER_CLASS;
    /** Латинская «x» перед кириллицей: «xлопок». */
    private static final Pattern LATIN_X = Pattern.compile("(?<![а-яё])x(?=[а-яё])", FLAGS);
    private static final Pattern SPACES = Pattern.compile("\\s+", UNICODE_CHARACTER_CLASS);
    private static final Pattern TRAILING_COLONS = Pattern.compile(":+$");
    private static final Pattern PACK = Pattern.compile("([0-9]+)\\s*(?:шт|пар)", FLAGS);

    private final NicheMatchingConfig config;

    public CardFeatureExtractor(NicheMatchingConfig config) {
        this.config = config;
    }

    public CardFeatures extract(Map<String, Object> card) {
        Map<String, String> rawChars = stringMap(card.get("characteristics_full"));
        // «Уход за вещами:» и «Уход за вещами» — один ключ; при повторе остаётся последний
        Map<String, String> chars = new LinkedHashMap<>();
        rawChars.forEach((key, value) -> chars.put(TRAILING_COLONS.matcher(key).replaceAll(""), value));

        String name = string(card.get("name"));
        String group = config.groupOf(cardType(rawChars, name));
        boolean foot = config.isFootGroup(group);

        Map<String, String> featureValues = new LinkedHashMap<>();
        for (String key : config.featuresOf(group)) {
            String value = chars.get(key);
            featureValues.put(key, value == null ? "" : normValue(value));
        }

        SizeRange size = SizeNormalizer.normalize(chars, foot);
        TemperatureRange temperature = TemperatureNormalizer.normalize(chars.get(TemperatureNormalizer.KEY));
        int pack = packCount(chars, name);
        Double price = priceOf(card);

        return new CardFeatures(
                String.valueOf(card.get("sku")),
                name,
                group,
                genders(rawChars, string(card.get("category_path")), name),
                featureValues,
                size.scale(), size.min(), size.max(),
                temperature.min(), temperature.max(),
                CompositionAnalyzer.cardNaturalShare(chars),
                pack,
                price == null || price == 0 ? null : price / pack,
                modelSkus(card),
                modelSizes(card, foot, pack));
    }

    /** «Тип» карточки; нет «Типа» — первое слово названия с заглавной буквы. */
    static String cardType(Map<String, String> rawChars, String name) {
        String type = rawChars.get("Тип");
        if (type != null && !type.isEmpty()) {
            return type;
        }
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        String word = SPACES.split(trimmed)[0];
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * Для кого вещь: «девочки», «мальчики» или оба. Источник — «Пол»; без него — раздел
     * каталога («Одежда для девочек / для мальчиков»), затем название («для девочки»;
     * «детская» — для всех). Пусто — неизвестно.
     */
    static Set<String> genders(Map<String, String> rawChars, String categoryPath, String name) {
        for (String text : new String[]{rawChars.get("Пол"), categoryPath, name}) {
            String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
            Set<String> found = new LinkedHashSet<>();
            if (lower.contains(GIRLS.substring(0, 5))) {
                found.add(GIRLS);
            }
            if (lower.contains(BOYS.substring(0, 5))) {
                found.add(BOYS);
            }
            if (!found.isEmpty()) {
                return found;
            }
        }
        String lowerName = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lowerName.contains("детск") ? new LinkedHashSet<>(List.of(GIRLS, BOYS)) : new LinkedHashSet<>();
    }

    /** Значение признака для текста: латинская «x» → «х», нижний регистр, схлопнутые пробелы. */
    static String normValue(String value) {
        String text = LATIN_X.matcher(value).replaceAll("х").toLowerCase(Locale.ROOT);
        return SPACES.matcher(text).replaceAll(" ").strip();
    }

    /**
     * Штук (пар) в упаковке: «Количество в упаковке», «Количество пар в упаковке»,
     * иначе «N шт» / «N пар» в названии, иначе 1.
     */
    static int packCount(Map<String, String> chars, String name) {
        for (String key : new String[]{"Количество в упаковке", "Количество пар в упаковке"}) {
            List<Double> ns = TextNumbers.numbers(chars.get(key));
            if (!ns.isEmpty() && ns.get(0) >= 1) {
                return (int) (double) ns.get(0);
            }
        }
        Matcher m = PACK.matcher(name == null ? "" : name);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return n >= 1 ? n : 1;
        }
        return 1;
    }

    /** sku модели: сама карточка и все значения её переключателей (размеры и цвета). */
    static Set<String> modelSkus(Map<String, Object> card) {
        Set<String> skus = new LinkedHashSet<>();
        skus.add(String.valueOf(card.get("sku")));
        for (Map<String, Object> aspect : aspects(card)) {
            for (Map<String, Object> value : values(aspect)) {
                if (truthy(value.get("sku"))) {
                    skus.add(String.valueOf(value.get("sku")));
                }
            }
        }
        return skus;
    }

    /** Размеры модели в наличии из переключателя «Размер»; цены — за штуку. */
    static List<ModelSize> modelSizes(Map<String, Object> card, boolean foot, int pack) {
        for (Map<String, Object> aspect : aspects(card)) {
            if (!"sizes".equals(aspect.get("type")) && !"size".equals(aspect.get("key"))) {
                continue;
            }
            List<ModelSize> sizes = new ArrayList<>();
            for (Map<String, Object> value : values(aspect)) {
                Object availability = value.get("availability");
                if (!truthy(value.get("sku")) || (availability != null && !"inStock".equals(availability))) {
                    continue;
                }
                String label = value.get("value") == null ? null : String.valueOf(value.get("value"));
                double[] size = SizeNormalizer.variantSize(label == null ? "" : label, foot);
                if (size != null) {
                    Double price = number(value.get("price"));
                    sizes.add(new ModelSize(String.valueOf(value.get("sku")), size[0], size[1],
                            price == null || price == 0 ? null : price / pack, label));
                }
            }
            return sizes;
        }
        return List.of();
    }

    // ---------- Доступ к карте карточки ----------

    private static Double priceOf(Map<String, Object> card) {
        return card.get("price") instanceof Map<?, ?> price ? number(price.get("price")) : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> aspects(Map<String, Object> card) {
        return card.get("aspects") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> values(Map<String, Object> aspect) {
        return aspect.get("values") instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (k != null && v != null) {
                    out.put(String.valueOf(k), String.valueOf(v));
                }
            });
        }
        return out;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Double number(Object value) {
        return value instanceof Number n ? n.doubleValue() : null;
    }

    private static boolean truthy(Object value) {
        return value != null && !String.valueOf(value).isEmpty();
    }
}