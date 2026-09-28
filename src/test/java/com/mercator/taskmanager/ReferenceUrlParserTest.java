package com.mercator.taskmanager;

import com.mercator.taskmanager.service.ReferenceUrlParser;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Извлечение sku из ссылки на карточку-референс Ozon. */
class ReferenceUrlParserTest {

    private final ReferenceUrlParser parser = new ReferenceUrlParser();

    // ---------- 1. Корректные ссылки ----------
    @Test
    void fullUrlWithSlug() {
        assertThat(parser.extractSku("ozon",
                "https://www.ozon.ru/product/bluzka-zhenskaya-1335434168/"))
                .isEqualTo("1335434168");
    }

    @Test
    void urlWithQueryParams() {
        assertThat(parser.extractSku("ozon",
                "https://www.ozon.ru/product/bluzka-1335434168/?at=abc&sh=xyz"))
                .isEqualTo("1335434168");
    }

    @Test
    void urlWithoutSlug() {
        assertThat(parser.extractSku("ozon", "https://ozon.ru/product/1335434168/"))
                .isEqualTo("1335434168");
    }

    @Test
    void urlWithoutTrailingSlash() {
        assertThat(parser.extractSku("ozon", "https://www.ozon.ru/product/bluzka-1335434168"))
                .isEqualTo("1335434168");
    }

    @Test
    void subdomainAndHttpAccepted() {
        assertThat(parser.extractSku("ozon", "http://m.ozon.ru/product/bluzka-1335434168/"))
                .isEqualTo("1335434168");
    }

    @Test
    void marketplaceTitleCaseInsensitive() {
        assertThat(parser.extractSku("Ozon", "https://www.ozon.ru/product/bluzka-1335434168/"))
                .isEqualTo("1335434168");
    }

    // ---------- 2. Отклоняемые ссылки ----------
    @Test
    void shortLinkRejected() {
        assertThatThrownBy(() -> parser.extractSku("ozon", "https://ozon.ru/t/AbCd12"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не на карточку товара Ozon");
    }

    @Test
    void foreignDomainRejected() {
        assertThatThrownBy(() -> parser.extractSku("ozon",
                "https://www.wildberries.ru/catalog/123456/detail.aspx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не ведёт на Ozon");
    }

    @Test
    void lookalikeDomainRejected() {
        assertThatThrownBy(() -> parser.extractSku("ozon",
                "https://fakeozon.ru/product/bluzka-1335434168/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не ведёт на Ozon");
    }

    @Test
    void nonHttpSchemeRejected() {
        assertThatThrownBy(() -> parser.extractSku("ozon",
                "ftp://www.ozon.ru/product/bluzka-1335434168/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректная ссылка");
    }

    @Test
    void notAUrlRejected() {
        assertThatThrownBy(() -> parser.extractSku("ozon", "не ссылка"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Некорректная ссылка");
    }

    @Test
    void otherMarketplaceNotSupported() {
        assertThatThrownBy(() -> parser.extractSku("wb",
                "https://www.wildberries.ru/catalog/123456/detail.aspx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("пока не поддерживаются");
    }
}