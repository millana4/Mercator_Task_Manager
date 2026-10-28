package com.mercator.taskmanager.assignment;

/**
 * Статусы сета (set.status, V18).
 *
 * draft      — черновик в админке: ни в подборе, ни в обходе, ни в аналитике не участвует;
 * collecting — идёт сбор: подбор по очереди, обход и расчёт есть, на дашборде не показывается;
 * active     — текущий сет категории: добор, выдача, подсказки ниш, дашборд;
 * retired    — выведен после переключения на новый сет, остаётся для истории.
 */
public final class SetStatus {

    public static final String DRAFT = "draft";
    public static final String COLLECTING = "collecting";
    public static final String ACTIVE = "active";
    public static final String RETIRED = "retired";

    private SetStatus() {
    }
}