package com.mercator.taskmanager.assignment;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Формулы задания на сбор и анализ — единственное место, где они записаны.
 * Повторяют расчётные колонки CSV задания детской одежды:
 *
 *   «Необх. кол-во для анализа кат.»   base         = доля подкатегории × карточек на анализ категории
 *   «С добавкой на анализ подкатегории» withAddition = max(base, минимум на подкатегорию), если
 *                                                      подкатегория отслеживается; иначе base
 *   «С поправкой на ротацию»           withRotation = withAddition × коэффициент ротации
 *   «Итог подкатегории с округлением»  total        = округление withRotation
 *   количество страты (план парсинга)  = округление(итог подкатегории × доля запроса)
 *   квота анализа категории            = округление(доля подкатегории × карточек на анализ × доля запроса)
 *
 * Округление математическое (половина — вверх), как в таблице и в аналитике.
 * Пустая доля запроса — единственный предмет подкатегории, доля 1.
 */
public final class AssignmentCalculator {

    /** Параметры задания сета: 200 / 30 / 1,35. */
    public record Params(int analysisCards, int minSubcategoryCards, BigDecimal rotationFactor) {
    }

    /** Расчётные колонки строки подкатегории. */
    public record SubcategoryFigures(BigDecimal base, BigDecimal withAddition,
                                     BigDecimal withRotation, int total) {
    }

    private AssignmentCalculator() {
    }

    public static SubcategoryFigures subcategory(BigDecimal share, boolean tracked, Params params) {
        BigDecimal base = share.multiply(BigDecimal.valueOf(params.analysisCards()));
        BigDecimal withAddition = tracked
                ? base.max(BigDecimal.valueOf(params.minSubcategoryCards()))
                : base;
        BigDecimal withRotation = withAddition.multiply(params.rotationFactor());
        return new SubcategoryFigures(base, withAddition, withRotation, roundHalfUp(withRotation));
    }

    public static int stratumCount(int subcategoryTotal, BigDecimal queryShare) {
        return roundHalfUp(BigDecimal.valueOf(subcategoryTotal).multiply(orOne(queryShare)));
    }

    public static int analysisQuota(BigDecimal share, BigDecimal queryShare, Params params) {
        return roundHalfUp(share
                .multiply(BigDecimal.valueOf(params.analysisCards()))
                .multiply(orOne(queryShare)));
    }

    static int roundHalfUp(BigDecimal value) {
        return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    private static BigDecimal orOne(BigDecimal queryShare) {
        return queryShare == null ? BigDecimal.ONE : queryShare;
    }
}