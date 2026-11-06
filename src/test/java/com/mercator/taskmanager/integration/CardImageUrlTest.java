package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.entity.StratumEntity;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Главное фото карточки (card.image_url): обход пишет новое или изменившееся фото,
 * ответ без фото прежнее не затирает; новая карточка из подбора в страту получает
 * фото сразу. Основа — NicheTestSupport: настоящие Postgres и Redis, ClickHouse
 * подменён, результаты подаются прямо в консьюмеры.
 */
class CardImageUrlTest extends NicheTestSupport {

    private static final String IMG_1 = "https://ir.ozone.ru/s3/multimedia-1-a/c600/1001.jpg";
    private static final String IMG_2 = "https://ir.ozone.ru/s3/multimedia-1-a/c600/1002.jpg";

    /** Активная карточка страты S75; возвращает её id. */
    private UUID stratumCardId() {
        createHintStratum(75);
        return uuid("SELECT id FROM card WHERE sku = ?", stratumSku(75));
    }

    /** Результат обхода карточки; imageUrl = null — поля нет (парсер старой версии). */
    private String crawlResult(UUID cardId, String imageUrl) {
        Map<String, Object> card = hoodie(stratumSku(75), 75);
        if (imageUrl != null) {
            card.put("image_url", imageUrl);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("task_id", UUID.randomUUID().toString());
        msg.put("card_id", cardId.toString());
        msg.put("sku", stratumSku(75));
        msg.put("geo", GEO);
        msg.put("parser_id", "it");
        msg.put("ok", true);
        msg.put("card", card);
        return MAPPER.writeValueAsString(msg);
    }

    private String imageUrl(UUID cardId) {
        return str("SELECT image_url FROM card WHERE id = ?", cardId);
    }

    @Test
    void crawlWritesImageUrl() {
        UUID cardId = stratumCardId();
        assertThat(imageUrl(cardId)).isNull();

        parseConsumer.handle(crawlResult(cardId, IMG_1));

        assertThat(imageUrl(cardId)).isEqualTo(IMG_1);
    }

    @Test
    void crawlWithoutImageKeepsStoredOne() {
        UUID cardId = stratumCardId();
        parseConsumer.handle(crawlResult(cardId, IMG_1));

        parseConsumer.handle(crawlResult(cardId, null));

        assertThat(imageUrl(cardId)).isEqualTo(IMG_1);
    }

    @Test
    void newImageReplacesOld() {
        UUID cardId = stratumCardId();
        parseConsumer.handle(crawlResult(cardId, IMG_1));

        parseConsumer.handle(crawlResult(cardId, IMG_2));

        assertThat(imageUrl(cardId)).isEqualTo(IMG_2);
    }

    @Test
    void newStratumCardFromSelectionGetsImage() {
        StratumEntity st = createHintStratum();
        Map<String, Object> card = hoodie(searchSku(91), 91);
        card.put("image_url", IMG_1);

        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("task_id", UUID.randomUUID().toString());
        msg.put("set_id", st.getSubcategory().getSet().getId().toString());
        msg.put("stratum_id", st.getId().toString());
        msg.put("geo", GEO);
        msg.put("parser_id", "it");
        msg.put("ok", true);
        msg.put("requested_count", 1);
        msg.put("found_count", 1);
        msg.put("cards", List.of(card));
        selectConsumer.handle(MAPPER.writeValueAsString(msg));

        assertThat(str("SELECT image_url FROM card WHERE sku = ?", searchSku(91))).isEqualTo(IMG_1);
    }
}