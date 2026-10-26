package com.mercator.taskmanager.service;

/** Доступ тестов из корневого пакета к package-private расчётам NicheRefillService. */
public final class NicheRefillServiceAccess {

    private NicheRefillServiceAccess() {
    }

    public static long interval(int emptyCount, long baseHours, long[] backoff) {
        return NicheRefillService.searchIntervalHours(emptyCount, baseHours, backoff);
    }

    public static long[] parse(String value) {
        return NicheRefillService.parseHours(value);
    }
}