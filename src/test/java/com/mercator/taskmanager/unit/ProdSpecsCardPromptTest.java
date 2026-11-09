package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.prod_specs.ProdSpecsCardPrompt;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Запрос шага 1: служебные характеристики не идут, двоеточие в названиях чистится,
 * описание сжимается и режется, отпечаток входных данных.
 */
class ProdSpecsCardPromptTest {

    private static Map<String, Object> card() {
        Map<String, String> chars = new LinkedHashMap<>();
        chars.put("Тип", "Худи");
        chars.put("Артикул", "123");
        chars.put("Цвет", "розовый");
        chars.put("Особенности модели:", "с капюшоном");
        chars.put("Размер производителя", "128");
        chars.put("Коллекция", "осень 2026");
        chars.put("Уход за вещами:", "стирка 30");
        chars.put("Модель", "прямая");
        chars.put("Код ТН ВЭД", "6110");
        chars.put("Страна-изготовитель", "Россия");

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", "Худи для девочки");
        content.put("category_path", "Одежда/Детям/Толстовки");
        content.put("characteristics_full", chars);
        content.put("description", "Тёплое худи\n\nс карманом   кенгуру");
        return content;
    }

    @Test
    void userPromptSkipsServiceCharacteristicsAndCleansColons() {
        String expected = String.join("\n",
                "Название: Худи для девочки",
                "Раздел маркетплейса: Одежда/Детям/Толстовки",
                "Характеристики:",
                "- Тип: Худи",
                "- Цвет: розовый",
                "- Особенности модели: с капюшоном",
                "- Модель: прямая",
                "Описание: Тёплое худи с карманом кенгуру") + "\n";

        assertThat(ProdSpecsCardPrompt.userPrompt(card())).isEqualTo(expected);
    }

    @Test
    void shortCharacteristicsAreUsedWhenFullIsMissing() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", "Худи");
        content.put("characteristics_full", Map.of());
        content.put("characteristics", Map.of("Цвет", "розовый", "Артикул", "1"));

        String prompt = ProdSpecsCardPrompt.userPrompt(content);

        assertThat(prompt).contains("- Цвет: розовый")
                .doesNotContain("Артикул")
                .doesNotContain("Раздел маркетплейса");
    }

    @Test
    void emptyValuesAreSkipped() {
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, String> chars = new LinkedHashMap<>();
        chars.put("Цвет", "  ");
        chars.put("Тип", "Худи");
        content.put("characteristics_full", chars);

        assertThat(ProdSpecsCardPrompt.userPrompt(content))
                .isEqualTo("Характеристики:\n- Тип: Худи\n");
    }

    @Test
    void duplicateNamesWithAndWithoutColonAreMerged() {
        Map<String, Object> content = new LinkedHashMap<>();
        Map<String, String> chars = new LinkedHashMap<>();
        chars.put("Рисунок", "полоска");
        chars.put("Рисунок:", "клетка");
        chars.put("Цвет", "белый");
        chars.put("Цвет:", "белый");
        content.put("characteristics_full", chars);

        assertThat(ProdSpecsCardPrompt.userPrompt(content))
                .contains("- Рисунок: полоска, клетка")
                .contains("- Цвет: белый\n")
                .doesNotContain("белый, белый");
    }

    @Test
    void longDescriptionIsTruncated() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("description", "а".repeat(2500));

        assertThat(ProdSpecsCardPrompt.userPrompt(content))
                .isEqualTo("Описание: " + "а".repeat(2000) + "…\n");
    }

    @Test
    void serviceCharacteristics() {
        assertThat(ProdSpecsCardPrompt.isServiceCharacteristic("Артикул")).isTrue();
        assertThat(ProdSpecsCardPrompt.isServiceCharacteristic("Вид выпуска товара")).isTrue();
        assertThat(ProdSpecsCardPrompt.isServiceCharacteristic("Код ТН ВЭД")).isTrue();
        assertThat(ProdSpecsCardPrompt.isServiceCharacteristic("Модель")).isFalse();
        assertThat(ProdSpecsCardPrompt.isServiceCharacteristic("Декоративные элементы")).isFalse();
    }

    @Test
    void inputHashDependsOnImageAndText() {
        String hash = ProdSpecsCardPrompt.inputHash("https://img/1.jpg", "текст");

        assertThat(hash).hasSize(64).matches("[0-9a-f]+");
        assertThat(ProdSpecsCardPrompt.inputHash("https://img/1.jpg", "текст")).isEqualTo(hash);
        assertThat(ProdSpecsCardPrompt.inputHash("https://img/2.jpg", "текст")).isNotEqualTo(hash);
        assertThat(ProdSpecsCardPrompt.inputHash("https://img/1.jpg", "другой")).isNotEqualTo(hash);
        assertThat(ProdSpecsCardPrompt.inputHash(null, "текст"))
                .isEqualTo(ProdSpecsCardPrompt.inputHash("", "текст"));
    }

    @Test
    void systemPromptRules() {
        String prompt = ProdSpecsCardPrompt.SYSTEM_PROMPT;

        assertThat(ProdSpecsCardPrompt.VERSION).isEqualTo("v2");
        assertThat(prompt).contains("только товар из названия")
                .contains("однотонный")
                .contains("в одном поле")
                .contains("\"extra\": []");
    }
}