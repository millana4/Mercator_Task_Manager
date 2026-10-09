package com.mercator.taskmanager;

import com.mercator.taskmanager.matching.CompositionAnalyzer;
import com.mercator.taskmanager.matching.SizeNormalizer;
import com.mercator.taskmanager.matching.SizeNormalizer.SizeRange;
import com.mercator.taskmanager.matching.SizeScale;
import com.mercator.taskmanager.matching.TemperatureNormalizer;
import com.mercator.taskmanager.matching.TemperatureNormalizer.TemperatureRange;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Разбор размера, температуры и доли натуральных — на характерных записях из данных. */
class CardFeatureParsersTest {

    private static SizeRange manufacturerSize(String value) {
        return SizeNormalizer.normalize(Map.of("Размер производителя", value), false);
    }

    @Test
    void heightAndGirth() {
        SizeRange r = manufacturerSize("(146)-76");
        assertEquals(146.0, r.min());
        assertEquals(146.0, r.max());
        assertEquals(76.0, r.girth());
        assertEquals(84.0, manufacturerSize("158-84-72").girth());
        // «Длина 58cm» — длина изделия, не рост
        SizeRange withLength = manufacturerSize("M: Длина 58cm, рост 90-105cm");
        assertEquals(92.0, withLength.min());
        assertEquals(104.0, withLength.max());
    }

    @Test
    void ageToHeight() {
        SizeRange years = manufacturerSize("12(2-3Y)");
        assertEquals(92.0, years.min());
        assertEquals(98.0, years.max());
        SizeRange months = manufacturerSize("3-6 месяцев");
        assertEquals(62.0, months.min());
        assertEquals(68.0, months.max());
        assertEquals(134.0, manufacturerSize("9 лет (42)").min());
        assertNull(manufacturerSize("2 ШТ").min());
    }

    @Test
    void footSize() {
        SizeRange eu = SizeNormalizer.normalize(Map.of("Российский размер", "33, 34, 35, 36"), true);
        assertEquals(SizeScale.FOOT, eu.scale());
        assertEquals(20.5, eu.min());
        assertEquals(22.5, eu.max());
        SizeRange baby = SizeNormalizer.normalize(Map.of("Российский размер", "19, 20, 18"), true);
        assertEquals(10.5, baby.min());
        assertEquals(12.0, baby.max());
        assertArrayEquals(new double[]{134, 134}, SizeNormalizer.variantSize("134 RU / 8-9", false));
    }

    @Test
    void temperature() {
        assertEquals(new TemperatureRange(-15.0, 10.0), TemperatureNormalizer.normalize("от -15 до +10"));
        assertEquals(new TemperatureRange(-25.0, null), TemperatureNormalizer.normalize("До -25°C"));
        assertEquals(new TemperatureRange(null, 15.0), TemperatureNormalizer.normalize("До +15°C"));
        assertEquals(new TemperatureRange(10.0, null), TemperatureNormalizer.normalize("выше +10°"));
        assertEquals(new TemperatureRange(-25.0, -5.0), TemperatureNormalizer.normalize("-5-25"));
        assertEquals(new TemperatureRange(5.0, 25.0), TemperatureNormalizer.normalize("+5 - 25"));
        assertEquals(new TemperatureRange(15.0, 15.0), TemperatureNormalizer.normalize("+15"));
        assertEquals(new TemperatureRange(null, null), TemperatureNormalizer.normalize("стирка при t не более 30°C"));
        assertEquals(new TemperatureRange(null, null), TemperatureNormalizer.normalize("30"));
    }

    @Test
    void naturalShare() {
        assertEquals(91.3, CompositionAnalyzer.naturalShare("95% хлопок, 9% эластан"));
        assertEquals(80.0, CompositionAnalyzer.naturalShare("80/20 хлопок/полиэстер"));
        assertEquals(100.0, CompositionAnalyzer.naturalShare("Хлопок 55%, 45% вискоза"));
        assertEquals(100.0, CompositionAnalyzer.naturalShare("верх: 100% хлопок; подкладка: 100% полиэстер"));
        assertEquals(100.0, CompositionAnalyzer.naturalShare("Xлопок 55%, Вискоза 45%"));
        assertEquals(30.0, CompositionAnalyzer.naturalShare("30% мериносовая шерсть, 70% ПАН"));
        assertEquals(0.0, CompositionAnalyzer.naturalShare("Полиэстер, мембрана"));
        assertNull(CompositionAnalyzer.naturalShare("Хлопок и полиэстер"));
        assertNull(CompositionAnalyzer.naturalShare("100%"));
    }
}