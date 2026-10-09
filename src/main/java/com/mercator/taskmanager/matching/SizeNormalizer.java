package com.mercator.taskmanager.matching;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.UNICODE_CASE;
import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Приведение размера карточки к единой шкале.
 *
 * Одежда — рост в см на сетке ростовок с шагом 6 см (50, 56 … 188). Источник по приоритету:
 * рост из «Российского размера», из «Размера производителя» (там же обхват груди: «(146)-76»),
 * из «Роста»; затем возраст из «Размера производителя» / «Российского размера», переведённый
 * в рост; затем «Минимальный / максимальный возраст ребенка».
 * Носки и пинетки — длина стопы в см: до 17 — уже см, от 18 — европейский размер обуви.
 *
 * Перенос ячейки 8 ноутбука разбора признаков; проверяется эталонами niche_matching_golden.jsonl.
 */
public final class SizeNormalizer {

    public static final double HEIGHT_STEP = 6.0;
    public static final double FOOT_STEP = 1.0;

    private static final int GRID_MIN = 50;
    private static final int GRID_MAX = 188;
    private static final int FLAGS = CASE_INSENSITIVE | UNICODE_CASE | UNICODE_CHARACTER_CLASS;

    private static final Pattern LENGTH = Pattern.compile("длина\\s*[0-9]+(?:[.,][0-9]+)?\\s*(?:см|cm)?", FLAGS);
    private static final Pattern MONTHS = Pattern.compile("мес|mec|\\dm\\b|\\dм\\b", FLAGS);
    private static final Pattern YEARS = Pattern.compile("лет|год|\\dл\\b|\\dл/|\\dг\\b|\\dг/|\\dy\\b", FLAGS);
    private static final Pattern YEARS_IN_BRACKETS = Pattern.compile("\\(([0-9]+)\\s*-\\s*([0-9]+)\\s*y\\)", FLAGS);

    private SizeNormalizer() {
    }

    /** Размер карточки в единой шкале; source — откуда взят. */
    public record SizeRange(SizeScale scale, Double min, Double max, Double girth, String source) {
    }

    /** Рост (мин, макс) и обхват груди из одной строки. */
    record HeightsAndGirth(double min, double max, Double girth) {
    }

    /**
     * Размер карточки по характеристикам (ключи без двоеточия в конце).
     *
     * @param foot размер группы — длина стопы (носки, пинетки)
     */
    public static SizeRange normalize(Map<String, String> chars, boolean foot) {
        String ru = chars.get("Российский размер");
        String mf = chars.get("Размер производителя");
        String growth = chars.get("Рост");

        if (foot) {
            List<Double> ns = new ArrayList<>();
            for (double n : TextNumbers.numbers(ru)) {
                ns.add(footCm(n));
            }
            if (!ns.isEmpty()) {
                return new SizeRange(SizeScale.FOOT, Collections.min(ns), Collections.max(ns), null,
                        "Российский размер");
            }
            return new SizeRange(SizeScale.FOOT, null, null, null, "нет числа в «Российский размер»");
        }

        HeightsAndGirth mfParsed = heightsAndGirth(mf);
        Double girth = mfParsed == null ? null : mfParsed.girth();
        String[][] heightSources = {{"Российский размер", ru}, {"Размер производителя", mf}, {"Рост", growth}};
        for (String[] source : heightSources) {
            HeightsAndGirth h = heightsAndGirth(source[1]);
            if (h != null) {
                return new SizeRange(SizeScale.HEIGHT, (double) snap(h.min()), (double) snap(h.max()), girth, source[0]);
            }
        }
        String[][] ageSources = {{"Размер производителя", mf}, {"Российский размер", ru}};
        for (String[] source : ageSources) {
            double[] age = ageMonths(source[1]);
            if (age != null) {
                int[] height = monthsToHeight(age[0], age[1]);
                return new SizeRange(SizeScale.HEIGHT, (double) snap(height[0]), (double) snap(height[1]), girth,
                        source[0] + " (возраст)");
            }
        }
        double[] ageMin = ageMonths(chars.get("Минимальный возраст ребенка"));
        double[] ageMax = ageMonths(chars.get("Максимальный возраст ребенка"));
        if (ageMin != null || ageMax != null) {
            double lo = (ageMin != null ? ageMin : ageMax)[0];
            double hi = (ageMax != null ? ageMax : ageMin)[1];
            int[] height = monthsToHeight(lo, hi);
            return new SizeRange(SizeScale.HEIGHT, (double) snap(height[0]), (double) snap(height[1]), girth,
                    "мин./макс. возраст");
        }
        return new SizeRange(SizeScale.HEIGHT, null, null, girth, "рост не указан");
    }

    /**
     * Размер значения переключателя «Размер»: «134 RU / 8-9» → рост 134; у носков и пинеток —
     * стопа, см. Берётся часть до «RU». → {мин, макс} или null.
     */
    public static double[] variantSize(String value, boolean foot) {
        String text = value == null ? "" : value;
        int ru = text.indexOf("RU");
        String ruPart = ru >= 0 ? text.substring(0, ru) : text;
        if (foot) {
            List<Double> ns = new ArrayList<>();
            for (double n : TextNumbers.numbers(ruPart)) {
                ns.add(footCm(n));
            }
            return ns.isEmpty() ? null : new double[]{Collections.min(ns), Collections.max(ns)};
        }
        HeightsAndGirth h = heightsAndGirth(ruPart);
        return h == null ? null : new double[]{snap(h.min()), snap(h.max())};
    }

    /** Ближайшая ростовка сетки; при равенстве — меньшая. */
    static int snap(double height) {
        int best = GRID_MIN;
        double bestDiff = Math.abs(GRID_MIN - height);
        for (int g = GRID_MIN + 6; g <= GRID_MAX; g += 6) {
            double diff = Math.abs(g - height);
            if (diff < bestDiff) {
                best = g;
                bestDiff = diff;
            }
        }
        return best;
    }

    /**
     * Рост и обхват груди из строки. Обхват — когда числа ≥ 40 сильно различаются:
     * рост — числа не меньше 70% наибольшего, обхват — первое из остальных.
     * Длина изделия («Длина 58cm») в рост не попадает. → null, если роста нет.
     */
    static HeightsAndGirth heightsAndGirth(String text) {
        String cleaned = LENGTH.matcher(text == null ? "" : text).replaceAll("");
        List<Double> big = new ArrayList<>();
        for (double n : TextNumbers.numbers(cleaned)) {
            if (n >= 40 && n <= 190) {
                big.add(n);
            }
        }
        if (big.isEmpty()) {
            return null;
        }
        double top = Collections.max(big);
        List<Double> heights = new ArrayList<>();
        Double girth = null;
        for (double n : big) {
            if (n >= 50 && n >= 0.7 * top) {
                heights.add(n);
            } else if (n < 0.7 * top && girth == null) {
                girth = n;
            }
        }
        if (heights.isEmpty()) {
            return null;
        }
        return new HeightsAndGirth(Collections.min(heights), Collections.max(heights), girth);
    }

    /**
     * Возраст (мин, макс) в месяцах: «3-6 месяцев», «8-10 лет», «2-4г/104», «12(2-3Y)», «7-8».
     * Числа без единиц — возраст в годах, только если не больше 16; «2 ШТ» — не возраст.
     */
    static double[] ageMonths(String text) {
        if (text == null) {
            return null;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.contains("шт")) {
            return null;
        }
        Matcher m = YEARS_IN_BRACKETS.matcher(t);
        if (m.find()) {
            return new double[]{Integer.parseInt(m.group(1)) * 12.0, Integer.parseInt(m.group(2)) * 12.0};
        }
        boolean hasYears = YEARS.matcher(t).find();
        boolean hasMonths = MONTHS.matcher(t).find() && !hasYears;
        double limit = hasMonths ? 36 : 18;
        List<Double> ns = new ArrayList<>();
        for (double n : TextNumbers.numbers(t)) {
            if (n <= limit) {
                ns.add(n);
            }
        }
        if (ns.isEmpty() || (!hasYears && !hasMonths && Collections.max(ns) > 16)) {
            return null;
        }
        double lo = Collections.min(ns);
        double hi = Collections.max(ns);
        return hasMonths ? new double[]{lo, hi} : new double[]{lo * 12, hi * 12};
    }

    /**
     * Возраст → рост: 0–1 мес 50–56, 1–3 56–62, 3–6 62–68, 6–9 68–74, 9–12 74–80,
     * 12–18 80–86, 18–24 86–92, дальше каждый год +6 см (2–3 года 92–98 … 15–16 лет 170–176).
     */
    static int[] monthsToHeight(double monthsLo, double monthsHi) {
        int lo = bracket(monthsLo);
        int hi = monthsHi > monthsLo ? bracket(Math.max(monthsLo, monthsHi - 1e-6)) + 6 : lo + 6;
        return new int[]{lo, hi};
    }

    private static int bracket(double months) {
        double[][] edges = {{1, 50}, {3, 56}, {6, 62}, {9, 68}, {12, 74}, {18, 80}, {24, 86}};
        for (double[] edge : edges) {
            if (months < edge[0]) {
                return (int) edge[1];
            }
        }
        return 92 + 6 * (int) (Math.floor(months / 12) - 2);
    }

    /**
     * Длина стопы в см: до 17 — уже см; от 18 — европейский размер обуви
     * (колодка = размер × 2/3 см, стопа на 1,5 см короче). Округление до 0,5 см.
     */
    static double footCm(double n) {
        double cm = n < 18 ? n : n * 2 / 3 - 1.5;
        return Math.rint(cm * 2) / 2;
    }
}