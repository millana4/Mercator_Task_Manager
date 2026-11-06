package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.repository.CardRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Поиск карточек по списку sku на маркетплейсе (команда разметки признаков):
 * настоящий SQL на настоящем Postgres. Основа — NicheTestSupport.
 */
class ProdSpecsCardLookupTest extends NicheTestSupport {

    @Autowired private CardRepository cardRepository;

    @Test
    void findsCardsBySkuOnMarketplace() {
        UUID idA = uuid("INSERT INTO card (marketplace_id, sku, name) VALUES (?, ?, ?) RETURNING id",
                ozonId(), prefix + "L1", "Худи 1");
        UUID idB = uuid("INSERT INTO card (marketplace_id, sku, name) VALUES (?, ?, ?) RETURNING id",
                ozonId(), prefix + "L2", "Худи 2");
        try {
            List<CardRepository.IdSku> found = cardRepository.findIdsBySkus("ozon",
                    List.of(prefix + "L1", prefix + "L2", prefix + "L3"));

            assertThat(found).extracting(CardRepository.IdSku::getSku)
                    .containsExactlyInAnyOrder(prefix + "L1", prefix + "L2");
            assertThat(found).extracting(CardRepository.IdSku::getId)
                    .containsExactlyInAnyOrder(idA, idB);
            assertThat(cardRepository.findIdsBySkus("wildberries", List.of(prefix + "L1"))).isEmpty();
        } finally {
            exec("DELETE FROM card WHERE id = ?", idA);
            exec("DELETE FROM card WHERE id = ?", idB);
        }
    }
}