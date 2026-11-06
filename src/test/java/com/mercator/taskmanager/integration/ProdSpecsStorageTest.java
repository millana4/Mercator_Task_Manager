package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.entity.ProdSpecsCardEntity;
import com.mercator.taskmanager.entity.LlmRequestLogEntity;
import com.mercator.taskmanager.repository.ProdSpecsCardRepository;
import com.mercator.taskmanager.repository.LlmRequestLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Хранилище рекомендаций по закупкам: признаки карточки (prod_specs_card) и журнал
 * обращений к нейросети (llm_request_log). Основа — NicheTestSupport (настоящие
 * Postgres и Redis, ClickHouse подменён).
 */
class ProdSpecsStorageTest extends NicheTestSupport {

    @Autowired private ProdSpecsCardRepository attributesRepository;
    @Autowired private LlmRequestLogRepository logRepository;

    @Test
    void prodSpecsCardIsStoredAndRemovedWithCard() {
        UUID cardId = uuid("INSERT INTO card (marketplace_id, sku, name) VALUES (?, ?, ?) RETURNING id",
                ozonId(), prefix + "P1", "Худи");
        try {
            Map<String, Object> attrs = new LinkedHashMap<>();
            attrs.put("silhouette", "оверсайз");
            attrs.put("decor", List.of("рюши", "бант"));
            attrs.put("extra", List.of(Map.of("name", "капюшон со шнурком", "value", "есть")));

            ProdSpecsCardEntity entity = new ProdSpecsCardEntity();
            entity.setCardId(cardId);
            entity.setInputHash("hash-1");
            entity.setPromptVersion("v1");
            entity.setModel("qwen3.6-35b-a3b");
            entity.setAttrs(attrs);
            attributesRepository.save(entity);

            ProdSpecsCardEntity read = attributesRepository.findById(cardId).orElseThrow();
            assertThat(read.getInputHash()).isEqualTo("hash-1");
            assertThat(read.getPromptVersion()).isEqualTo("v1");
            assertThat(read.getAttrs().get("silhouette")).isEqualTo("оверсайз");
            assertThat(read.getAttrs().get("decor")).isEqualTo(List.of("рюши", "бант"));
            assertThat(read.getAttrs().get("extra"))
                    .isEqualTo(List.of(Map.of("name", "капюшон со шнурком", "value", "есть")));
            assertThat(read.getCreatedAt()).isNotNull();

            // Данные карточки изменились: строка заменяется, а не добавляется
            read.setInputHash("hash-2");
            attributesRepository.save(read);
            assertThat(attributesRepository.findById(cardId).orElseThrow().getInputHash())
                    .isEqualTo("hash-2");
            assertThat(attributesRepository.count()).isGreaterThanOrEqualTo(1);

            exec("DELETE FROM card WHERE id = ?", cardId);
            assertThat(count("SELECT count(*) FROM prod_specs_card WHERE card_id = ?", cardId)).isZero();
        } finally {
            exec("DELETE FROM card WHERE id = ?", cardId);
        }
    }

    @Test
    void llmRequestIsLogged() {
        LlmRequestLogEntity entry = new LlmRequestLogEntity();
        entry.setPurpose("prod_specs_card");
        entry.setModel("qwen3.6-35b-a3b");
        entry.setPromptTokens(1200);
        entry.setCompletionTokens(300);
        entry.setTotalTokens(1500);
        entry.setSuccess(true);
        entry.setDurationMs(850);
        entry.setRefType("sku");
        entry.setRefId(prefix + "P1");
        LlmRequestLogEntity saved = logRepository.save(entry);
        try {
            LlmRequestLogEntity read = logRepository.findById(saved.getId()).orElseThrow();
            assertThat(read.getPurpose()).isEqualTo("prod_specs_card");
            assertThat(read.getPromptTokens()).isEqualTo(1200);
            assertThat(read.getCompletionTokens()).isEqualTo(300);
            assertThat(read.getTotalTokens()).isEqualTo(1500);
            assertThat(read.getSuccess()).isTrue();
            assertThat(read.getError()).isNull();
            assertThat(read.getRefId()).isEqualTo(prefix + "P1");
            assertThat(read.getCreatedAt()).isNotNull();
        } finally {
            exec("DELETE FROM llm_request_log WHERE id = ?", saved.getId());
        }
    }
}