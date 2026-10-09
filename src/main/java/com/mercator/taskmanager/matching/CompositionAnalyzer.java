package com.mercator.taskmanager.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.UNICODE_CHARACTER_CLASS;

/**
 * Доля натуральных материалов, % — из «Состава материала», а если по нему не определяется —
 * из «Материала». Натуральные — природные и целлюлозные волокна (вискоза, модал, лиоцелл,
 * бамбук); синтетические — остальные волокна и покрытия.
 *
 * Основная ткань — материалы с процентами по порядку, пока сумма не наберёт 100%
 * (подкладка, отделка, второй вариант состава дальше не учитываются). Сумма не 100% —
 * доля от суммы. Без процентов: все материалы одного класса — 100 или 0, смесь — не определяется.
 *
 * Перенос ячейки 12 ноутбука разбора признаков; проверяется эталонами.
 */
public final class CompositionAnalyzer {

    public static final String COMPOSITION_KEY = "Состав материала";
    public static final String MATERIAL_KEY = "Материал";

    /** Порядок важен: при совпадении в одной позиции берётся первая альтернатива. */
    private static final Map<String, String> NATURAL = new LinkedHashMap<>();
    private static final Map<String, String> SYNTHETIC = new LinkedHashMap<>();

    static {
        NATURAL.put("хлопок", "хлоп|хлолок");
        NATURAL.put("лён", "(?<![а-яё])л[её]н(?![а-яё])");
        NATURAL.put("шерсть", "меринос\\w*(?:\\s+шерст\\w*)?|шерст");
        NATURAL.put("кашемир", "кашемир");
        NATURAL.put("шёлк", "ш[её]лк");
        NATURAL.put("вискоза", "вискоз");
        NATURAL.put("модал", "модал");
        NATURAL.put("лиоцелл", "лиоцел|тенсел");
        NATURAL.put("бамбук", "бамбук");
        NATURAL.put("натуральный мех", "натуральн\\w*\\s+мех|мех\\s+(?:енот|кролик|лис|норк|овчин)");
        SYNTHETIC.put("полиэстер",
                "полиэст|полиэфир|п/э|(?<![а-яё])пэ(?![а-яё])|polyester|велсофт|флис|синтепон|политекс|синтетик");
        SYNTHETIC.put("эластан", "эластан|лайкр|спандекс|спандэкс");
        SYNTHETIC.put("полиамид", "полиамид|нейлон|капрон");
        SYNTHETIC.put("акрил", "акрил|(?<![а-яё])пан(?![а-яё])");
        SYNTHETIC.put("полиуретан", "полиуретан|(?<![а-яё])пу(?![а-яё])");
        SYNTHETIC.put("мембрана", "мембран|тинсулейт|слайтекс");
        SYNTHETIC.put("резина", "резин");
        SYNTHETIC.put("искусственный мех", "искусственн\\w*\\s+мех|мех\\s+искусственн|эко-?мех");
    }

    /** Имя группы в выражении (n0…, s0…) → (материал, натуральный). */
    private static final Map<String, Material> GROUPS = new LinkedHashMap<>();
    private static final Pattern MATERIAL;

    static {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (var e : NATURAL.entrySet()) {
            String group = "n" + i++;
            GROUPS.put(group, new Material(e.getKey(), true));
            sb.append(sb.isEmpty() ? "" : "|").append("(?<").append(group).append(">").append(e.getValue()).append(")");
        }
        i = 0;
        for (var e : SYNTHETIC.entrySet()) {
            String group = "s" + i++;
            GROUPS.put(group, new Material(e.getKey(), false));
            sb.append("|(?<").append(group).append(">").append(e.getValue()).append(")");
        }
        MATERIAL = Pattern.compile(sb.toString(), UNICODE_CHARACTER_CLASS);
    }

    private static final Pattern PERCENT = Pattern.compile("[0-9]+(?:[.,][0-9]+)?");
    /** «80/20 хлопок/полиэстер». */
    private static final Pattern SLASH = Pattern.compile("([0-9]+(?:/[0-9]+)+)\\s+([а-яёa-z]+(?:/[а-яёa-z]+)+)",
            UNICODE_CHARACTER_CLASS);
    private static final Pattern LATIN_X = Pattern.compile("(?<![а-яё])xлоп");
    private static final Pattern DENSITY = Pattern.compile("плотность[^;,.]*");
    private static final Pattern POLYVISCOSE = Pattern.compile("поливискоз\\w*", UNICODE_CHARACTER_CLASS);

    private CompositionAnalyzer() {
    }

    record Material(String name, boolean natural) {
    }

    /** Материал записи с процентом (null — процента нет). */
    record Part(String name, boolean natural, Double percent) {
    }

    /** Доля натуральных по характеристикам карточки (ключи без двоеточия в конце); null — не определена. */
    public static Double cardNaturalShare(Map<String, String> chars) {
        Double share = naturalShare(chars.get(COMPOSITION_KEY));
        return share != null ? share : naturalShare(chars.get(MATERIAL_KEY));
    }

    /** Доля натуральных материалов по одной записи, %; null — не определена. */
    public static Double naturalShare(String text) {
        if (text == null) {
            return null;
        }
        List<Part> parts = parts(clean(text));
        if (parts.isEmpty()) {
            return null;
        }

        List<Part> withPercent = new ArrayList<>();
        for (Part p : parts) {
            if (p.percent() != null && p.percent() > 0) {
                withPercent.add(p);
            }
        }
        if (!withPercent.isEmpty()) {
            double total = 0;
            double natural = 0;
            for (Part p : withPercent) {
                total += p.percent();
                if (p.natural()) {
                    natural += p.percent();
                }
                if (total >= 99.5) {
                    break;
                }
            }
            return Math.rint(natural / total * 100 * 10) / 10;
        }

        Set<Boolean> classes = new HashSet<>();
        for (Part p : parts) {
            classes.add(p.natural());
        }
        if (classes.equals(Set.of(true))) {
            return 100.0;
        }
        if (classes.equals(Set.of(false))) {
            return 0.0;
        }
        return null;                                                     // смесь без процентов
    }

    static String clean(String text) {
        String t = text.toLowerCase(Locale.ROOT).replace("％", "%");
        t = LATIN_X.matcher(t).replaceAll("хлоп");                      // латинская «x» вместо «х»
        t = DENSITY.matcher(t).replaceAll(" ");                         // «Плотность 330 г/м²» — не состав
        t = POLYVISCOSE.matcher(t).replaceAll("полиэстер вискоза");     // поливискоза — смесь
        return t;
    }

    /**
     * Материалы с процентами в порядке записи. Пары собираются по соседству: «100% хлопок»,
     * «хлопок 100%», «Хлопок 55%, 45% вискоза», «50%хлопок50%полиэстер»; формат
     * «80/20 хлопок/полиэстер» — отдельно.
     */
    static List<Part> parts(String t) {
        List<Part> parts = new ArrayList<>();
        Matcher slash = SLASH.matcher(t);
        if (slash.find()) {
            String[] percents = slash.group(1).split("/");
            String[] names = slash.group(2).split("/");
            for (int i = 0; i < Math.min(percents.length, names.length); i++) {
                Matcher mm = MATERIAL.matcher(names[i]);
                if (mm.find()) {
                    Material material = materialOf(mm);
                    parts.add(new Part(material.name(), material.natural(), Double.parseDouble(percents[i])));
                }
            }
            return parts;
        }

        record Token(int start, Material material, Double number) {
        }
        List<Token> tokens = new ArrayList<>();
        Matcher mm = MATERIAL.matcher(t);
        while (mm.find()) {
            tokens.add(new Token(mm.start(), materialOf(mm), null));
        }
        Matcher pm = PERCENT.matcher(t);
        while (pm.find()) {
            tokens.add(new Token(pm.start(), null, Double.parseDouble(pm.group().replace(',', '.'))));
        }
        tokens.sort(Comparator.comparingInt(Token::start));

        Token pending = null;
        for (Token token : tokens) {
            boolean isMaterial = token.material() != null;
            if (pending == null) {
                pending = token;
            } else if (pending.material() != null && !isMaterial) {
                parts.add(new Part(pending.material().name(), pending.material().natural(), token.number()));
                pending = null;
            } else if (pending.material() == null && isMaterial) {
                parts.add(new Part(token.material().name(), token.material().natural(), pending.number()));
                pending = null;
            } else if (isMaterial) {
                // материал без процента — «хлопок, полиэстер»; повтор того же («велсофт-полиэстер») — один
                if (!pending.material().name().equals(token.material().name())) {
                    parts.add(new Part(pending.material().name(), pending.material().natural(), null));
                }
                pending = token;
            } else {
                pending = token;                                         // два числа подряд
            }
        }
        if (pending != null && pending.material() != null) {
            parts.add(new Part(pending.material().name(), pending.material().natural(), null));
        }
        return parts;
    }

    private static Material materialOf(Matcher m) {
        for (var e : GROUPS.entrySet()) {
            if (m.group(e.getKey()) != null) {
                return e.getValue();
            }
        }
        throw new IllegalStateException("совпадение без группы материала: " + m.group());
    }
}