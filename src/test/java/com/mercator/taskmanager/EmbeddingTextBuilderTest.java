package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.CardSnapshotRepository.SnapshotText;
import com.mercator.taskmanager.embedding.EmbeddingTextBuilder;
import com.mercator.taskmanager.entity.NicheEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Текст карточки для эмбеддинга — одинаковый для референса и кандидатов. */
class EmbeddingTextBuilderTest {

    // ---------- 1. Из данных карточки от парсера ----------
    @Test
    void fromSnapshotBuildsTextWithoutBrand() {
        String text = EmbeddingTextBuilder.fromSnapshot(snapshot("Лёгкая   блузка\nиз хлопка"));

        assertThat(text).isEqualTo("Блузка женская. Раздел: Одежда/Женщинам. "
                + "Характеристики: Цвет: белый; Состав: хлопок. "
                + "Описание: Лёгкая блузка из хлопка");
        assertThat(text).doesNotContain("TestBrand");
    }

    @Test
    void characteristicsLimitedToFifteen() {
        Map<String, Object> s = new LinkedHashMap<>();
        Map<String, String> chars = new LinkedHashMap<>();
        for (int i = 1; i <= 20; i++) {
            chars.put("Ключ" + i, "значение" + i);
        }
        s.put("name", "Товар");
        s.put("characteristics", chars);

        String text = EmbeddingTextBuilder.fromSnapshot(s);

        assertThat(text).contains("Ключ15: значение15").doesNotContain("Ключ16");
    }

    @Test
    void descriptionTruncatedTo300Chars() {
        String text = EmbeddingTextBuilder.fromSnapshot(snapshot("я".repeat(500)));

        String description = text.substring(text.indexOf("Описание: ") + "Описание: ".length());
        assertThat(description).hasSize(300);
    }

    @Test
    void fromSnapshotNullGivesEmptyText() {
        assertThat(EmbeddingTextBuilder.fromSnapshot(null)).isEmpty();
    }

    // ---------- 2. Из снимка в ClickHouse ----------
    @Test
    void fromClickhouseBuildsSameFormat() {
        SnapshotText t = new SnapshotText("Блузка женская", "Одежда/Женщинам",
                "Цвет: белый; Состав: хлопок", "Лёгкая блузка из хлопка");

        assertThat(EmbeddingTextBuilder.fromClickhouse(t))
                .isEqualTo(EmbeddingTextBuilder.fromSnapshot(snapshot("Лёгкая блузка из хлопка")));
    }

    @Test
    void fromClickhouseNullGivesEmptyText() {
        assertThat(EmbeddingTextBuilder.fromClickhouse(null)).isEmpty();
    }

    // ---------- 3. Референс ниши ----------
    @Test
    void referenceWithCardUsesCard() {
        NicheEntity n = new NicheEntity();
        n.setReferenceSnapshot(snapshot("Лёгкая блузка"));
        n.setDescription("белая блузка");
        n.setSearchQuery("блузка женская белая");

        assertThat(EmbeddingTextBuilder.forReference(n))
                .isEqualTo(EmbeddingTextBuilder.fromSnapshot(snapshot("Лёгкая блузка")));
    }

    @Test
    void referenceWithoutCardUsesDescriptionAndQuery() {
        NicheEntity n = new NicheEntity();
        n.setDescription("белая хлопковая блузка");
        n.setSearchQuery("блузка женская белая хлопок");

        assertThat(EmbeddingTextBuilder.forReference(n))
                .isEqualTo("белая хлопковая блузка. блузка женская белая хлопок");
    }

    @Test
    void referenceWithoutCardAndQueryUsesDescription() {
        NicheEntity n = new NicheEntity();
        n.setDescription("белая хлопковая блузка");

        assertThat(EmbeddingTextBuilder.forReference(n)).isEqualTo("белая хлопковая блузка");
    }

    // ---------- вспомогательное ----------
    private static Map<String, Object> snapshot(String description) {
        Map<String, String> chars = new LinkedHashMap<>();
        chars.put("Цвет", "белый");
        chars.put("Состав", "хлопок");
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("name", "Блузка женская");
        s.put("brand", "TestBrand");
        s.put("category_path", "Одежда/Женщинам");
        s.put("characteristics", chars);
        s.put("description", description);
        return s;
    }
}