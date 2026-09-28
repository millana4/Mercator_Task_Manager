package com.mercator.taskmanager.service;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Извлечение sku из ссылки на карточку-референс.
 *
 * Ozon: путь /product/<название>-<артикул>/ или /product/<артикул>/
 * на домене ozon.ru или его поддомене. Короткие ссылки (ozon.ru/t/...)
 * артикула не содержат — отклоняются.
 */
@Component
public class ReferenceUrlParser {

    private static final Pattern OZON_PRODUCT_PATH =
            Pattern.compile("^/product/(?:[^/]*-)?(\\d+)/?$");

    /**
     * @param marketplace название маркетплейса из справочника (например, ozon)
     * @param url         ссылка на карточку
     * @return sku
     * @throws IllegalArgumentException если ссылка некорректна или не ведёт на карточку
     */
    public String extractSku(String marketplace, String url) {
        if (!"ozon".equalsIgnoreCase(marketplace)) {
            throw new IllegalArgumentException(
                    "Ссылки на референс для маркетплейса '" + marketplace + "' пока не поддерживаются");
        }

        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Некорректная ссылка: " + url);
        }

        String scheme = uri.getScheme();
        String host = uri.getHost();
        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("Некорректная ссылка: " + url);
        }

        host = host.toLowerCase(Locale.ROOT);
        if (!(host.equals("ozon.ru") || host.endsWith(".ozon.ru"))) {
            throw new IllegalArgumentException("Ссылка не ведёт на Ozon: " + host);
        }

        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher m = OZON_PRODUCT_PATH.matcher(path);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                    "Ссылка не на карточку товара Ozon: нужна полная ссылка вида "
                            + "https://www.ozon.ru/product/...-<артикул>/");
        }
        return m.group(1);
    }
}