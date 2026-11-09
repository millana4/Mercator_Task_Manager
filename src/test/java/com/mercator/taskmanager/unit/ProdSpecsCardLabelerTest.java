package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.entity.ProdSpecsCardEntity;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmGateway;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.prod_specs.ProdSpecsCardPrompt;
import com.mercator.taskmanager.prod_specs.LabelResult;
import com.mercator.taskmanager.prod_specs.ProdSpecsCardLabeler;
import com.mercator.taskmanager.repository.ProdSpecsCardRepository;
import com.mercator.taskmanager.repository.CardRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Разметка одной карточки (шаг 1): пропуски, отпечаток данных, запрос к модели с фото,
 * запись признаков, ошибки. Репозитории и шлюз — моки.
 */
class ProdSpecsCardLabelerTest {

    private static final String IMAGE = "https://ir.ozone.ru/s3/multimedia-1-n/c600/1.jpg";
    private static final String ANSWER = "{\"garment_type\": \"худи\", \"decor\": [\"рюши\"]}";

    private final CardRepository cardRepository = mock(CardRepository.class);
    private final ProdSpecsCardRepository attributesRepository = mock(ProdSpecsCardRepository.class);
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final ProdSpecsCardLabeler labeler = new ProdSpecsCardLabeler(
            cardRepository, attributesRepository, gateway, "qwen3.6-35b-a3b", 1500);
    private final UUID id = UUID.randomUUID();

    private static Map<String, Object> content() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("name", "Худи для девочки");
        content.put("characteristics_full", Map.of("Цвет", "розовый"));
        return content;
    }

    private void cardWith(String imageUrl, Map<String, Object> content) {
        CardEntity card = new CardEntity();
        card.setId(id);
        card.setSku("123");
        card.setImageUrl(imageUrl);
        card.setContentTxt(content);
        when(cardRepository.findById(id)).thenReturn(Optional.of(card));
    }

    private void modelAnswers(String text) {
        when(gateway.call(any(), any(), any(), any()))
                .thenReturn(new LlmResponse(text, "qwen3.6-35b-a3b", 1500, 1200, 300));
    }

    private static ProdSpecsCardEntity stored(String hash, String version) {
        ProdSpecsCardEntity entity = new ProdSpecsCardEntity();
        entity.setInputHash(hash);
        entity.setPromptVersion(version);
        entity.setModel("qwen3.6-35b-a3b");
        entity.setAttrs(Map.of("garment_type", "худи"));
        return entity;
    }

    @Test
    void unknownCardIsNotFound() {
        when(cardRepository.findById(id)).thenReturn(Optional.empty());

        assertThat(labeler.label(id)).isEqualTo(LabelResult.NOT_FOUND);
        verifyNoInteractions(gateway);
    }

    @Test
    void cardWithoutImageIsSkipped() {
        cardWith(null, content());
        assertThat(labeler.label(id)).isEqualTo(LabelResult.NO_IMAGE);

        cardWith("   ", content());
        assertThat(labeler.label(id)).isEqualTo(LabelResult.NO_IMAGE);
        verifyNoInteractions(gateway);
    }

    @Test
    void cardWithoutContentIsSkipped() {
        cardWith(IMAGE, null);
        assertThat(labeler.label(id)).isEqualTo(LabelResult.NO_CONTENT);

        cardWith(IMAGE, Map.of());
        assertThat(labeler.label(id)).isEqualTo(LabelResult.NO_CONTENT);
        verifyNoInteractions(gateway);
    }

    @Test
    void newCardIsLabeled() {
        cardWith(IMAGE, content());
        when(attributesRepository.findById(id)).thenReturn(Optional.empty());
        modelAnswers(ANSWER);

        assertThat(labeler.label(id)).isEqualTo(LabelResult.LABELED);

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).call(eq(LlmGateway.PROD_SPECS_CARD), eq("sku"), eq("123"), request.capture());
        assertThat(request.getValue().imageUrl()).isEqualTo(IMAGE);
        assertThat(request.getValue().model()).isEqualTo("qwen3.6-35b-a3b");
        assertThat(request.getValue().systemPrompt()).isEqualTo(ProdSpecsCardPrompt.SYSTEM_PROMPT);
        assertThat(request.getValue().userPrompt()).isEqualTo(ProdSpecsCardPrompt.userPrompt(content()));
        assertThat(request.getValue().maxTokens()).isEqualTo(1500);

        ArgumentCaptor<ProdSpecsCardEntity> saved = ArgumentCaptor.forClass(ProdSpecsCardEntity.class);
        verify(attributesRepository).save(saved.capture());
        ProdSpecsCardEntity entity = saved.getValue();
        assertThat(entity.getCardId()).isEqualTo(id);
        assertThat(entity.getInputHash()).isEqualTo(
                ProdSpecsCardPrompt.inputHash(IMAGE, ProdSpecsCardPrompt.userPrompt(content())));
        assertThat(entity.getPromptVersion()).isEqualTo(ProdSpecsCardPrompt.VERSION);
        assertThat(entity.getModel()).isEqualTo("qwen3.6-35b-a3b");
        assertThat(entity.getAttrs().get("garment_type")).isEqualTo("худи");
        assertThat(entity.getAttrs().get("decor")).isEqualTo(List.of("рюши"));
    }

    @Test
    void unchangedCardIsUpToDate() {
        cardWith(IMAGE, content());
        String hash = ProdSpecsCardPrompt.inputHash(IMAGE, ProdSpecsCardPrompt.userPrompt(content()));
        when(attributesRepository.findById(id))
                .thenReturn(Optional.of(stored(hash, ProdSpecsCardPrompt.VERSION)));

        assertThat(labeler.label(id)).isEqualTo(LabelResult.UP_TO_DATE);
        verifyNoInteractions(gateway);
        verify(attributesRepository, never()).save(any());
    }

    @Test
    void changedInputsRelabelAndUpdateSameRow() {
        cardWith(IMAGE, content());
        ProdSpecsCardEntity existing = stored("old-hash", ProdSpecsCardPrompt.VERSION);
        when(attributesRepository.findById(id)).thenReturn(Optional.of(existing));
        modelAnswers(ANSWER);

        assertThat(labeler.label(id)).isEqualTo(LabelResult.LABELED);

        ArgumentCaptor<ProdSpecsCardEntity> saved = ArgumentCaptor.forClass(ProdSpecsCardEntity.class);
        verify(attributesRepository).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(existing);
        assertThat(existing.getInputHash()).isNotEqualTo("old-hash");
        assertThat(existing.getAttrs().get("decor")).isEqualTo(List.of("рюши"));
    }

    @Test
    void otherPromptVersionDoesNotRelabel() {
        cardWith(IMAGE, content());
        String hash = ProdSpecsCardPrompt.inputHash(IMAGE, ProdSpecsCardPrompt.userPrompt(content()));
        when(attributesRepository.findById(id)).thenReturn(Optional.of(stored(hash, "v0")));

        assertThat(labeler.label(id)).isEqualTo(LabelResult.UP_TO_DATE);
        verifyNoInteractions(gateway);
    }

    @Test
    void unusableAnswerFailsWithoutSaving() {
        cardWith(IMAGE, content());
        when(attributesRepository.findById(id)).thenReturn(Optional.empty());
        modelAnswers("Не могу определить.");

        assertThat(labeler.label(id)).isEqualTo(LabelResult.FAILED);
        verify(attributesRepository, never()).save(any());
    }

    @Test
    void timeoutIsFailed() {
        cardWith(IMAGE, content());
        when(attributesRepository.findById(id)).thenReturn(Optional.empty());
        when(gateway.call(any(), any(), any(), any()))
                .thenThrow(new LlmException(LlmException.Kind.TIMEOUT, "таймаут"));

        assertThat(labeler.label(id)).isEqualTo(LabelResult.FAILED);
        verify(attributesRepository, never()).save(any());
    }

    @Test
    void authErrorIsRethrown() {
        cardWith(IMAGE, content());
        when(attributesRepository.findById(id)).thenReturn(Optional.empty());
        when(gateway.call(any(), any(), any(), any()))
                .thenThrow(new LlmException(LlmException.Kind.AUTH, "нет ключей"));

        assertThatThrownBy(() -> labeler.label(id)).isInstanceOfSatisfying(LlmException.class,
                e -> assertThat(e.getKind()).isEqualTo(LlmException.Kind.AUTH));
    }
}