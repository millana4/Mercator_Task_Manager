package com.mercator.taskmanager.assignment;

/**
 * Сезон сета (set.season_type + set.season_year, V18) и его название для людей:
 * spring_summer + 2026 → «Spring Summer 2026», autumn_winter + 2027 → «Autumn Winter 2027».
 *
 * Следующий сезон: Spring Summer 2026 → Autumn Winter 2027 (продаётся с осени 2026),
 * Autumn Winter 2027 → Spring Summer 2027.
 */
public final class SeasonType {

    public static final String SPRING_SUMMER = "spring_summer";
    public static final String AUTUMN_WINTER = "autumn_winter";

    private SeasonType() {
    }

    /** Название сезона; у сета без сезона — null. */
    public static String title(String seasonType, Short seasonYear) {
        if (seasonType == null || seasonYear == null) {
            return null;
        }
        String name = switch (seasonType) {
            case SPRING_SUMMER -> "Spring Summer";
            case AUTUMN_WINTER -> "Autumn Winter";
            default -> throw new IllegalArgumentException("Неизвестный сезон: " + seasonType);
        };
        return name + " " + seasonYear;
    }

    /** Тип следующего сезона. */
    public static String nextType(String seasonType) {
        return switch (seasonType) {
            case SPRING_SUMMER -> AUTUMN_WINTER;
            case AUTUMN_WINTER -> SPRING_SUMMER;
            default -> throw new IllegalArgumentException("Неизвестный сезон: " + seasonType);
        };
    }

    /** Год следующего сезона: за весной-летом идёт осень-зима следующего года. */
    public static short nextYear(String seasonType, short seasonYear) {
        return switch (seasonType) {
            case SPRING_SUMMER -> (short) (seasonYear + 1);
            case AUTUMN_WINTER -> seasonYear;
            default -> throw new IllegalArgumentException("Неизвестный сезон: " + seasonType);
        };
    }
}