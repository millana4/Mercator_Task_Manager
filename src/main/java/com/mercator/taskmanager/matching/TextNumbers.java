package com.mercator.taskmanager.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Числа из текста: «1 999», «11,5», «92-98» → [1, 999], [11.5], [92, 98]. */
final class TextNumbers {

    private static final Pattern NUMBER = Pattern.compile("[0-9]+(?:[.,][0-9]+)?");

    private TextNumbers() {
    }

    static List<Double> numbers(String text) {
        List<Double> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            out.add(Double.parseDouble(m.group().replace(',', '.')));
        }
        return out;
    }
}