package com.mercator.taskmanager.assignment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Представления задания для админки (в JSON — snake_case). Расчётные колонки
 * подкатегории (base, with_addition, with_rotation, total) — как в CSV задания;
 * у сетов без параметров (одежда) они пустые.
 */
public final class AssignmentViews {

    private AssignmentViews() {
    }

    /** Шапка сета и итоги задания. */
    public record SetSummary(UUID id, String status, String seasonType, Short seasonYear,
                             String seasonTitle, Integer analysisCards, Integer minSubcategoryCards,
                             BigDecimal rotationFactor, int subcategories, int trackedSubcategories,
                             int strata, int plannedCards, int analysisQuota, OffsetDateTime createdAt,
                             OffsetDateTime collectingAt, OffsetDateTime activatedAt) {
    }

    /** Страта: предмет, запрос, доли и вычисленные количество и квота анализа. */
    public record StratumView(UUID id, String item, String query, BigDecimal queryShare,
                              boolean isSeasonal, BigDecimal baseShare, int count,
                              Integer analysisQuota) {
    }

    /** Подкатегория с расчётными колонками и стратами. */
    public record SubcategoryView(UUID id, String title, String segment, Integer layer,
                                  BigDecimal share, boolean tracked, BigDecimal base,
                                  BigDecimal withAddition, BigDecimal withRotation, Integer total,
                                  List<StratumView> strata) {
    }

    /** Задание сета целиком. */
    public record SetView(String category, String geo, String marketplace, SetSummary set,
                          List<SubcategoryView> subcategories) {
    }

    /** Готовность аналитики сета на уровне категории. */
    public record Readiness(Boolean ewma, Integer ewmaCards, Boolean macd, Integer macdCards,
                            boolean ready) {
    }

    /** Ход сбора: страты с отправленным подбором, набранные карточки, готовность. */
    public record Progress(UUID setId, int strataTotal, int strataSent, int plannedCards,
                           int collectedCards, Readiness readiness, String readinessError) {
    }

    /** Сеты связки для экрана админки. */
    public record Overview(String category, String geo, String marketplace, boolean seasonalSwitch,
                           SetSummary active, SetSummary draft, SetSummary collecting,
                           Progress progress) {
    }
}