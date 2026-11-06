package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.prod_specs.ProdSpecsCardParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Разбор ответа шага 1: чистка значений, формы списков и extra,
 * неизвестные ключи не пропадают, пустой ответ — ошибка.
 */
class ProdSpecsCardParserTest {

    @Test
    void cleansValuesAndKeepsAllFields() {
        Map<String, Object> attrs = ProdSpecsCardParser.parse("""
                {"garment_type": "  Худи ", "silhouette": "Оверсайз.", "length": null, "sleeve": "",
                 "neckline": "капюшон", "closure": ["Молния", "молния", " кнопки "], "hardware": [],
                 "decor": ["Рюши", "бантики"], "pockets": "кенгуру", "fabric": "футер",
                 "print": "без принта", "colors": ["Розовый", "белый"], "style": "кэжуал", "extra": []}
                """);

        assertThat(attrs.get("garment_type")).isEqualTo("худи");
        assertThat(attrs.get("silhouette")).isEqualTo("оверсайз");
        assertThat(attrs.get("length")).isNull();
        assertThat(attrs.get("sleeve")).isNull();
        assertThat(attrs.get("closure")).isEqualTo(List.of("молния", "кнопки"));
        assertThat(attrs.get("hardware")).isEqualTo(List.of());
        assertThat(attrs.get("decor")).isEqualTo(List.of("рюши", "бантики"));
        assertThat(attrs.get("colors")).isEqualTo(List.of("розовый", "белый"));
        assertThat(attrs.get("extra")).isEqualTo(List.of());
    }

    @Test
    void missingFieldsArePresentAsEmpty() {
        Map<String, Object> attrs = ProdSpecsCardParser.parse("{\"garment_type\": \"худи\"}");

        assertThat(attrs.keySet()).containsAll(ProdSpecsCardParser.SCALAR_FIELDS)
                .containsAll(ProdSpecsCardParser.LIST_FIELDS)
                .contains("extra");
        assertThat(attrs.get("decor")).isEqualTo(List.of());
        assertThat(attrs.get("style")).isNull();
    }

    @Test
    void jsonInMarkdownFenceIsParsed() {
        Map<String, Object> attrs = ProdSpecsCardParser.parse(
                "```json\n{\"garment_type\": \"платье\"}\n```");

        assertThat(attrs.get("garment_type")).isEqualTo("платье");
    }

    @Test
    void listFieldAsStringIsSplitByCommas() {
        Map<String, Object> attrs = ProdSpecsCardParser.parse(
                "{\"decor\": \"рюши, банты ,Рюши\"}");

        assertThat(attrs.get("decor")).isEqualTo(List.of("рюши", "банты"));
    }

    @Test
    void scalarFieldAsListIsJoined() {
        Map<String, Object> attrs = ProdSpecsCardParser.parse(
                "{\"fabric\": [\"футер\", \"флис\"]}");

        assertThat(attrs.get("fabric")).isEqualTo("футер, флис");
    }

    @Test
    void unknownKeysMoveToExtra() {
        Map<String, Object> attrs = ProdSpecsCardParser.parse("""
                {"garment_type": "худи", "капюшон": "со шнурком", "reasons_note": ["а", "б"],
                 "nested": {"a": 1}, "nothing": null}
                """);

        assertThat(attrs.get("extra")).isEqualTo(List.of(
                Map.of("name", "капюшон", "value", "со шнурком"),
                Map.of("name", "reasons note", "value", "а, б")));
    }

    @Test
    void extraAcceptsPairsStringsAndMap() {
        Map<String, Object> pairs = ProdSpecsCardParser.parse("""
                {"garment_type": "худи",
                 "extra": [{"name": "Бант на спине", "value": "Есть"},
                           {"name": "подкладка", "value": ""}, "молния YKK"]}
                """);
        assertThat(pairs.get("extra")).isEqualTo(List.of(
                Map.of("name", "бант на спине", "value", "есть"),
                Map.of("name", "подкладка", "value", "есть"),
                Map.of("name", "молния ykk", "value", "есть")));

        Map<String, Object> dict = ProdSpecsCardParser.parse(
                "{\"garment_type\": \"худи\", \"extra\": {\"Капюшон\": \"со шнурком\"}}");
        assertThat(dict.get("extra")).isEqualTo(List.of(
                Map.of("name", "капюшон", "value", "со шнурком")));
    }

    @Test
    void extraIsDistinctByNameAndCapped() {
        Map<String, Object> distinct = ProdSpecsCardParser.parse("""
                {"garment_type": "худи",
                 "extra": [{"name": "бант", "value": "а"}, {"name": "Бант", "value": "б"}]}
                """);
        assertThat(distinct.get("extra")).isEqualTo(List.of(Map.of("name", "бант", "value", "а")));

        StringBuilder many = new StringBuilder("{\"garment_type\": \"худи\", \"extra\": [");
        for (int i = 0; i < 20; i++) {
            many.append(i > 0 ? "," : "").append("\"признак").append(i).append('"');
        }
        many.append("]}");
        assertThat((List<?>) ProdSpecsCardParser.parse(many.toString()).get("extra")).hasSize(15);
    }

    @Test
    void answerWithoutJsonFails() {
        assertThatThrownBy(() -> ProdSpecsCardParser.parse("Не могу определить."))
                .isInstanceOf(LlmException.class).hasMessageContaining("нет JSON");
        assertThatThrownBy(() -> ProdSpecsCardParser.parse(null))
                .isInstanceOf(LlmException.class).hasMessageContaining("нет JSON");
    }

    @Test
    void brokenJsonFails() {
        assertThatThrownBy(() -> ProdSpecsCardParser.parse("{ нет }"))
                .isInstanceOf(LlmException.class).hasMessageContaining("JSON не разобран");
    }

    @Test
    void emptyAttributesFail() {
        assertThatThrownBy(() -> ProdSpecsCardParser.parse(
                "{\"garment_type\": null, \"decor\": [], \"extra\": []}"))
                .isInstanceOf(LlmException.class).hasMessageContaining("пустой набор признаков");
    }
}