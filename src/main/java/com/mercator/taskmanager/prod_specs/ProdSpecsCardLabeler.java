package com.mercator.taskmanager.prod_specs;

import com.mercator.taskmanager.entity.ProdSpecsCardEntity;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmGateway;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.repository.ProdSpecsCardRepository;
import com.mercator.taskmanager.repository.CardRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * Шаг 1 рекомендаций по закупкам: признаки одной карточки по фото и тексту.
 *
 * Карточка без адреса фото или без полного набора пропускается. Отпечаток входных данных
 * (адрес фото и текст запроса) совпали с сохранёнными — запрос не
 * отправляется. Иначе модель получает фото ссылкой и текст, ответ разбирается и пишется
 * в prod_specs_card: строка карточки заменяется.
 *
 * Ошибка модели (таймаут, непригодный ответ) — FAILED, карточка остаётся без признаков.
 * Ошибка авторизации пробрасывается: продолжать пачку нет смысла.
 */
@Service
public class ProdSpecsCardLabeler {

    private static final Logger log = LoggerFactory.getLogger(ProdSpecsCardLabeler.class);

    private final CardRepository cardRepository;
    private final ProdSpecsCardRepository attributesRepository;
    private final LlmGateway gateway;
    private final String model;
    private final int maxTokens;

    public ProdSpecsCardLabeler(CardRepository cardRepository,
                                ProdSpecsCardRepository attributesRepository,
                                LlmGateway gateway,
                                @Value("${taskmanager.prod_specs.model:qwen3.6-35b-a3b}") String model,
                                @Value("${taskmanager.prod_specs.max-tokens:1500}") int maxTokens) {
        this.cardRepository = cardRepository;
        this.attributesRepository = attributesRepository;
        this.gateway = gateway;
        this.model = model;
        this.maxTokens = maxTokens;
    }

    /**
     * Разметить карточку.
     *
     * @throws LlmException только AUTH (нет ключей или отказ в доступе)
     */
    public LabelResult label(UUID cardId) {
        CardEntity card = cardRepository.findById(cardId).orElse(null);
        if (card == null) {
            return LabelResult.NOT_FOUND;
        }
        String imageUrl = card.getImageUrl();
        if (imageUrl == null || imageUrl.isBlank()) {
            return LabelResult.NO_IMAGE;
        }
        Map<String, Object> content = card.getContentTxt();
        if (content == null || content.isEmpty()) {
            return LabelResult.NO_CONTENT;
        }

        String userPrompt = ProdSpecsCardPrompt.userPrompt(content);
        String inputHash = ProdSpecsCardPrompt.inputHash(imageUrl, userPrompt);
        ProdSpecsCardEntity entity = attributesRepository.findById(cardId).orElse(null);
        if (entity != null && inputHash.equals(entity.getInputHash())) {
            return LabelResult.UP_TO_DATE;
        }

        LlmResponse response;
        Map<String, Object> attrs;
        try {
            response = gateway.call(LlmGateway.PROD_SPECS_CARD, "sku", card.getSku(),
                    LlmRequest.withImage(model, ProdSpecsCardPrompt.SYSTEM_PROMPT,
                            userPrompt, imageUrl, maxTokens));
            attrs = ProdSpecsCardParser.parse(response.content());
        } catch (LlmException e) {
            if (e.getKind() == LlmException.Kind.AUTH) {
                throw e;
            }
            log.warn("Признаки карточки sku={}: не получены ({}): {}",
                    card.getSku(), e.getKind(), e.getMessage());
            return LabelResult.FAILED;
        }

        if (entity == null) {
            entity = new ProdSpecsCardEntity();
            entity.setCardId(cardId);
        }
        entity.setInputHash(inputHash);
        entity.setPromptVersion(ProdSpecsCardPrompt.VERSION);
        entity.setModel(response.model());
        entity.setAttrs(attrs);
        attributesRepository.save(entity);
        log.info("Признаки карточки sku={} записаны (model={}, tokens={})",
                card.getSku(), response.model(), response.tokens());
        return LabelResult.LABELED;
    }
}