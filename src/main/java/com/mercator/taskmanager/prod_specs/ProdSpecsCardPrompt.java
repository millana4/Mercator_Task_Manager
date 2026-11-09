package com.mercator.taskmanager.prod_specs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Запрос шага 1 рекомендаций по закупкам: признаки одной карточки по фото и тексту.
 *
 * Модели уходят название, раздел маркетплейса, характеристики (без служебных: артикул,
 * размеры, происхождение, упаковка, уход) и описание. Название характеристики чистится
 * от завершающего двоеточия («Рисунок:» и «Рисунок» — одно поле). Фото передаётся
 * отдельно, ссылкой.
 *
 * VERSION меняется при правке SYSTEM_PROMPT и только записывается в prod_specs_card
 * (какой промпт дал признаки): уже размеченные карточки заново не размечаются.
 *
 * v2: описывается только товар из названия (не весь образ на фото); «однотонный» вместо
 * «без принта»; застёжка и фурнитура не дублируются; extra не повторяет поля схемы.
 */
public final class ProdSpecsCardPrompt {

    public static final String VERSION = "v2";

    /** Описание карточки обрезается до этой длины. */
    static final int MAX_DESCRIPTION_CHARS = 2000;

    /** Служебные характеристики (названия без двоеточия, в нижнем регистре). */
    private static final Set<String> SERVICE_CHARACTERISTICS = Set.of(
            "артикул", "код тру", "сертификат", "гарантия", "вид выпуска товара",
            "российский размер", "размер производителя", "размер на модели",
            "рост модели на фото", "параметры модели на фото (ог-от-об)", "рост",
            "тип ростовки", "размер джинсов", "размер по вороту", "размеры, мм",
            "минимальный возраст ребенка", "максимальный возраст ребенка",
            "коллекция", "страна-изготовитель", "маркировка", "бренд в одежде и обуви",
            "количество в упаковке", "количество пар в упаковке", "комплектация",
            "уход за вещами");

    public static final String SYSTEM_PROMPT = """
            Ты помогаешь закупщикам одежды на маркетплейсе. По главному фото товара, \
            которое приложено к сообщению, и тексту карточки опиши признаки изделия, \
            значимые для закупки: фасон, детали, фурнитуру, декор, ткань, принт. \
            По этим признакам закупщики формируют требования к товару.

            Правила:
            - Описывай только товар из названия карточки. На фото может быть модель в полном \
            образе: другую одежду, аксессуары, обувь, фон, позу и надписи на фоне не описывай.
            - Внешний вид (фасон, декор, принт, цвет, карманы) бери с фото, состав и название \
            ткани — из текста.
            - Признак, который нельзя определить, оставь пустым: null, для списков — []. \
            Ничего не выдумывай.
            - Значения короткие, на русском, в нижнем регистре: одно–три слова. \
            Одинаковые вещи называй одинаковыми словами.
            - Каждый признак записывай в одном поле. Пуговицы, молнии и кнопки, которые \
            застёгивают изделие, указывай только в closure, а в hardware — фурнитуру, не \
            связанную с застёжкой (заклёпки, пряжки, люверсы, шнурки). Деталь рукава, воротника \
            или силуэта пиши в само поле (например, sleeve: «длинный с манжетом»), а не в extra.
            - В extra добавь значимые для закупки признаки, которых нет в списке полей ниже, \
            как пары {"name": "...", "value": "..."}. Если таких признаков нет — [].

            Поля:
            - garment_type: вид изделия (худи, брюки карго, платье)
            - silhouette: фасон и силуэт (прямой, оверсайз, приталенный, трапеция, зауженный)
            - length: длина изделия (мини, миди, макси, укороченное, стандартная)
            - sleeve: рукав (длинный, короткий, реглан, без рукавов, фонарик)
            - neckline: воротник и вырез (капюшон, стойка, v-образный, круглый, отложной)
            - closure: список застёжек (молния, пуговицы, кнопки, завязки, резинка, без застёжки)
            - hardware: список фурнитуры без застёжки (заклёпки, пряжки, люверсы, шнурки)
            - decor: список декоративных элементов (рюши, бантики, вышивка, аппликация, \
            кружево, стразы, помпоны, оборки, нашивки)
            - pockets: карманы (нет, кенгуру, прорезные, накладные, боковые)
            - fabric: ткань и фактура (футер, рубчик, флис, джинса, трикотаж, велюр)
            - print: принт; «однотонный», если рисунка нет, иначе тип и короткое описание \
            (полоска, клетка, цветочный, надпись, персонаж)
            - colors: список основных цветов самого изделия
            - style: стиль (кэжуал, спортивный, школьный, классический, романтичный)
            - extra: список пар {"name": "...", "value": "..."}

            Ответь строго одним JSON-объектом, без пояснений и без markdown:
            {"garment_type": null, "silhouette": null, "length": null, "sleeve": null, \
            "neckline": null, "closure": [], "hardware": [], "decor": [], "pockets": null, \
            "fabric": null, "print": null, "colors": [], "style": null, "extra": []}
            """;

    private ProdSpecsCardPrompt() {
    }

    /** Текст запроса по карточке (card.content_txt: карта с ключами в snake_case). */
    public static String userPrompt(Map<String, Object> content) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "Название", content.get("name"));
        appendLine(sb, "Раздел маркетплейса", content.get("category_path"));

        Map<String, String> chars = characteristics(content);
        if (!chars.isEmpty()) {
            sb.append("Характеристики:\n");
            chars.forEach((name, value) -> sb.append("- ").append(name).append(": ")
                    .append(value).append('\n'));
        }

        String description = description(content.get("description"));
        if (description != null) {
            sb.append("Описание: ").append(description).append('\n');
        }
        return sb.toString();
    }

    /** Отпечаток входных данных карточки: адрес фото и текст запроса. */
    public static String inputHash(String imageUrl, String userPrompt) {
        String source = (imageUrl == null ? "" : imageUrl) + "\n" + userPrompt;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Название характеристики без завершающего двоеточия и лишних пробелов. */
    public static String cleanName(String key) {
        return key.strip().replaceAll("\\s+", " ").replaceAll("[:\\s]+$", "");
    }

    /** Служебная ли характеристика; name — уже очищенное название. */
    public static boolean isServiceCharacteristic(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SERVICE_CHARACTERISTICS.contains(lower)
                || name.replaceAll("[\\s\\-.]", "").toUpperCase(Locale.ROOT).contains("ТНВЭД");
    }

    // ---------- Разбор карточки ----------

    private static Map<String, String> characteristics(Map<String, Object> content) {
        Object source = content.get("characteristics_full");
        if (!(source instanceof Map<?, ?> full) || full.isEmpty()) {
            source = content.get("characteristics");
        }
        Map<String, String> out = new LinkedHashMap<>();
        if (!(source instanceof Map<?, ?> map)) {
            return out;
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            String name = cleanName(String.valueOf(entry.getKey()));
            String value = String.valueOf(entry.getValue()).strip();
            if (name.isEmpty() || value.isEmpty() || isServiceCharacteristic(name)) {
                continue;
            }
            out.merge(name, value, (a, b) -> a.equals(b) ? a : a + ", " + b);
        }
        return out;
    }

    private static String description(Object value) {
        if (!(value instanceof String s) || s.isBlank()) {
            return null;
        }
        String text = s.replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_DESCRIPTION_CHARS
                ? text : text.substring(0, MAX_DESCRIPTION_CHARS) + "…";
    }

    private static void appendLine(StringBuilder sb, String label, Object value) {
        if (value instanceof String s && !s.isBlank()) {
            sb.append(label).append(": ").append(s.strip()).append('\n');
        }
    }
}