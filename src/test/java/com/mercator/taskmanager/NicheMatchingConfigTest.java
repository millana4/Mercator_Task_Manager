package com.mercator.taskmanager;

import com.mercator.taskmanager.matching.NicheMatchingConfig;
import com.mercator.taskmanager.matching.NicheMatchingProperties;
import com.mercator.taskmanager.matching.NicheMatchingProperties.Group;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Конфиг подбора аналогов: чтение niche-matching.yml и правила поверх него. */
class NicheMatchingConfigTest {

    private final NicheMatchingConfig config = MatchingConfigLoader.load();

    @Test
    void configIsRead() {
        assertEquals(26, config.groupCount());
        assertEquals(0.90, config.textSimFloor(), 1e-9);
        assertEquals(0.99, config.textSimCeil(), 1e-9);
        var w = config.weights();
        assertEquals(1.0, w.text() + w.price() + w.size() + w.natural() + w.temperature(), 1e-9);
        assertEquals(0.55, w.text(), 1e-9);
    }

    @Test
    void typeGroups() {
        assertEquals("Куртка", config.groupOf("Пуховик"));
        assertEquals("Куртка", config.groupOf("Парка"));
        assertEquals("Рубашка", config.groupOf("Блузка"));
        assertEquals("Брюки", config.groupOf("Брюки"));
        assertEquals("Джинсы", config.groupOf("Джинсы"));
        // «Тип» вне групп — своя группа
        assertEquals("Пальто", config.groupOf("Пальто"));
        assertEquals(null, config.groupOf(null));
    }

    @Test
    void groupFeatures() {
        List<String> trousers = config.featuresOf("Брюки");
        assertTrue(trousers.contains("Модель"));
        assertFalse(trousers.contains("Пол"));
        assertFalse(trousers.contains("Уход за вещами"));
        assertFalse(trousers.contains("Тип"));
        // группа без своих страт — признаки по умолчанию
        assertEquals(config.featuresOf("Пальто"), config.featuresOf("Бомбер"));
        assertTrue(config.featuresOf("Пальто").contains("Материал"));
    }

    @Test
    void footGroupsAndDuplicateKeys() {
        assertTrue(config.isFootGroup("Носки"));
        assertTrue(config.isFootGroup("Пинетки"));
        assertFalse(config.isFootGroup("Колготки"));
        assertTrue(config.ignoredForDuplicates("Цвет"));
        assertFalse(config.ignoredForDuplicates("Материал"));
    }

    @Test
    void typeInTwoGroupsIsRejected() {
        NicheMatchingProperties p = MatchingConfigLoader.loadProperties();
        List<Group> broken = new java.util.ArrayList<>(p.groups());
        broken.add(new Group("Дубль", List.of("Пуховик"), List.of("Материал")));
        NicheMatchingProperties bad = new NicheMatchingProperties(p.weights(), p.textSimFloor(),
                p.textSimCeil(), p.priceZeroRatio(), p.tempZeroDiff(), p.dupPriceTolerance(),
                p.dupIgnoreKeys(), p.footGroups(), p.defaultFeatures(), broken);

        assertThrows(IllegalStateException.class, () -> new NicheMatchingConfig(bad));
    }
}