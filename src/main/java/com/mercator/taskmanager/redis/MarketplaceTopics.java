package com.mercator.taskmanager.redis;

import java.util.Map;

/**
 * Сопоставление названия маркетплейса (как в справочнике) и латинского кода.
 * Код входит в ключи Redis-списков (parse:tasks:<маркетплейс>:<гео>):
 * в ключе не должно быть кириллицы и пробелов. Добавить площадку =
 * добавить строку в MARKETPLACE_TO_CODE.
 */
public final class MarketplaceTopics {

    private MarketplaceTopics() {}  // утилитный класс, экземпляры не нужны

    private static final Map<String, String> MARKETPLACE_TO_CODE = Map.of(
            "Ozon", "ozon",
            "ozon", "ozon",
            "Детский мир", "detmir",
            "Wildberries", "wb",
            "Яндекс Маркет", "yandex"
    );

    /** Код маркетплейса для ключа Redis. Бросает ошибку, если название неизвестно. */
    public static String codeOf(String marketplace) {
        String code = MARKETPLACE_TO_CODE.get(marketplace);
        if (code == null) {
            throw new IllegalArgumentException(
                    "Неизвестный маркетплейс '" + marketplace + "' — нет кода для ключа Redis. "
                            + "Добавь его в MarketplaceTopics.MARKETPLACE_TO_CODE.");
        }
        return code;
    }
}
