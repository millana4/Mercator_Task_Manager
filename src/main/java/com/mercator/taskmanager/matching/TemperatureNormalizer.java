package com.mercator.taskmanager.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Температурный режим → (мин °C, макс °C).
 * «от -15 до +10» → −15, 10; «До -25°C» → −25, —; «выше +10°» → 10, —;
 * «-15 -30» → −30, −15; «+15» → 15, 15. Температура стирки отбрасывается.
 *
 * Перенос ячейки 10 ноутбука разбора признаков; проверяется эталонами.
 */
public final class TemperatureNormalizer {

    public static final String KEY = "Температурный режим";

    /** Знак — только вплотную к числу; «−» (минус) тоже знак, тире и « - » — разделитель. */
    private static final Pattern NUMBER = Pattern.compile("(?<!\\d)([+\\-−]?)([0-9]+(?:[.,][0-9]+)?)",
            UNICODE_CHARACTER_CLASS);
    private static final Pattern FROM_TO = Pattern.compile("\\bот\\b|\\bдо\\b|выше|ниже", UNICODE_CHARACTER_CLASS);
    private static final Pattern LOWER_BOUND = Pattern.compile("выше|\\bот\\b", UNICODE_CHARACTER_CLASS);
    private static final Pattern UPPER_BOUND = Pattern.compile("\\bдо\\b|ниже", UNICODE_CHARACTER_CLASS);

    private TemperatureNormalizer() {
    }

    /** Границы температуры, °C; null — границы нет. */
    public record TemperatureRange(Double min, Double max) {
        static final TemperatureRange EMPTY = new TemperatureRange(null, null);
    }

    public static TemperatureRange normalize(String text) {
        if (text == null) {
            return TemperatureRange.EMPTY;
        }
        String t = text.toLowerCase(Locale.ROOT).replace("°", " ").replace("℃", " ");
        List<String> signs = new ArrayList<>();
        List<Double> numbers = new ArrayList<>();
        Matcher m = NUMBER.matcher(t);
        while (m.find()) {
            signs.add(m.group(1));
            numbers.add(Double.parseDouble(m.group(2).replace(',', '.')));
        }
        if (numbers.isEmpty()) {
            return TemperatureRange.EMPTY;
        }

        // Температура стирки: «стирка», «не выше 30», «30» без знака и без «от/до»
        boolean hasFromTo = FROM_TO.matcher(t).find();
        boolean allUnsigned = signs.stream().allMatch(String::isEmpty);
        if (t.contains("стир") || t.contains("не выше")
                || (allUnsigned && Collections.max(numbers) >= 30 && !hasFromTo)) {
            return TemperatureRange.EMPTY;
        }

        List<Double> values = new ArrayList<>();
        for (int i = 0; i < numbers.size(); i++) {
            String sign = signs.get(i);
            values.add(sign.equals("-") || sign.equals("−") ? -numbers.get(i) : numbers.get(i));
        }
        // «-15 -30», «-5-25»: второе число без знака после отрицательного — тоже отрицательное
        if (values.size() == 2 && values.get(0) < 0 && signs.get(1).isEmpty() && !hasFromTo) {
            values.set(1, -values.get(1));
        }

        if (values.size() >= 2) {
            return new TemperatureRange(Collections.min(values), Collections.max(values));
        }
        double v = values.get(0);
        if (LOWER_BOUND.matcher(t).find()) {
            return new TemperatureRange(v, null);                        // «от», «выше»
        }
        if (UPPER_BOUND.matcher(t).find()) {
            return v <= 0 ? new TemperatureRange(v, null) : new TemperatureRange(null, v);
        }
        if (v < 0) {
            return new TemperatureRange(v, null);                        // одно отрицательное
        }
        return new TemperatureRange(v, v);                               // одно значение
    }
}