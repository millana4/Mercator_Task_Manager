package com.mercator.taskmanager.integration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Неуспешный обход: ошибка разбора на стороне парсера (ValidationError) карточку не штрафует,
 * остальные сбои по-прежнему переводят её в stale (сбой смены формата Ozon 24.11.2026).
 */
class ParseFailureTest extends NicheTestSupport {

    @AfterEach
    void cleanUpCards() {
        exec("DELETE FROM card WHERE sku LIKE ?", prefix + "%");
    }

    private UUID newCard(String sku) {
        return uuid("INSERT INTO card (marketplace_id, sku, name, status, failed_attempts) "
                        + "VALUES (?, ?, ?, 'active', 0) RETURNING id",
                ozonId(), sku, "Худи " + sku);
    }

    private String failure(UUID cardId, String sku, String error) {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("task_id", UUID.randomUUID().toString());
        msg.put("card_id", cardId.toString());
        msg.put("sku", sku);
        msg.put("geo", GEO);
        msg.put("parser_id", "it");
        msg.put("ok", false);
        msg.put("error", error);
        return MAPPER.writeValueAsString(msg);
    }

    @Test
    void parserSideValidationErrorDoesNotPenalizeCard() {
        String sku = prefix + "V1";
        UUID id = newCard(sku);

        parseConsumer.handle(failure(id, sku,
                "ValidationError: 1 validation error for OzonCard\nbrand\n  Input should be a valid string"));

        assertThat(cardStatus(sku)).isEqualTo("active");
        assertThat(count("SELECT failed_attempts FROM card WHERE id = ?", id)).isZero();
    }

    @Test
    void otherFailureStillMakesCardStale() {
        String sku = prefix + "V2";
        UUID id = newCard(sku);

        parseConsumer.handle(failure(id, sku, "TimeoutException: page load"));

        assertThat(cardStatus(sku)).isEqualTo("stale");
        assertThat(count("SELECT failed_attempts FROM card WHERE id = ?", id)).isEqualTo(1);
    }
}