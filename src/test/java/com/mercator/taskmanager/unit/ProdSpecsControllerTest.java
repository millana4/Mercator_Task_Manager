package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.controller.ProdSpecsController;
import com.mercator.taskmanager.controller.ProdSpecsController.LabelCardsRequest;
import com.mercator.taskmanager.controller.ProdSpecsController.LabelCardsResponse;
import com.mercator.taskmanager.controller.ProdSpecsController.LlmCallRequest;
import com.mercator.taskmanager.controller.ProdSpecsController.LlmCallResponse;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmGateway;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.prod_specs.ProdSpecsLabelQueue;
import com.mercator.taskmanager.repository.CardRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Команды рекомендаций по закупкам: разметка карточек по списку sku и вызов модели
 * для анализа страты. Репозиторий, очередь и шлюз — моки; контроллер вызывается напрямую.
 */
class ProdSpecsControllerTest {

    private final CardRepository cardRepository = mock(CardRepository.class);
    private final ProdSpecsLabelQueue queue = mock(ProdSpecsLabelQueue.class);
    private final LlmGateway gateway = mock(LlmGateway.class);
    private final ProdSpecsController controller =
            new ProdSpecsController(cardRepository, queue, gateway, "qwen-test", 4000);

    private static CardRepository.IdSku row(UUID id, String sku) {
        return new CardRepository.IdSku() {
            @Override
            public UUID getId() {
                return id;
            }

            @Override
            public String getSku() {
                return sku;
            }
        };
    }

    private static int status(ResponseEntity<?> response) {
        return response.getStatusCode().value();
    }

    private static Object error(ResponseEntity<?> response) {
        return ((Map<?, ?>) response.getBody()).get("error");
    }

    private static LlmCallRequest llmRequest(String purpose, String system, String user, Integer maxTokens) {
        return new LlmCallRequest(purpose, "stratum", "s-1", system, user, maxTokens);
    }

    // ---------- Разметка карточек ----------

    @Test
    void labelCardsAcceptsAndReportsMissing() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(cardRepository.findIdsBySkus(any(), any()))
                .thenReturn(List.of(row(id1, "111"), row(id2, "222")));
        when(queue.enqueue(any())).thenAnswer(inv -> ((Collection<?>) inv.getArgument(0)).size());

        ResponseEntity<?> response = controller.labelCards(
                new LabelCardsRequest(Arrays.asList(" 111 ", "222", "111", null, " ", "333"), " ozon "));

        assertThat(status(response)).isEqualTo(202);
        LabelCardsResponse body = (LabelCardsResponse) response.getBody();
        assertThat(body.requested()).isEqualTo(3);
        assertThat(body.found()).isEqualTo(2);
        assertThat(body.accepted()).isEqualTo(2);
        assertThat(body.missing()).containsExactly("333");
        verify(cardRepository).findIdsBySkus("ozon", List.of("111", "222", "333"));
        ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(queue).enqueue(ids.capture());
        assertThat(new ArrayList<>(ids.getValue())).containsExactly(id1, id2);
    }

    @Test
    void labelCardsCountsOnlyNewlyAccepted() {
        when(cardRepository.findIdsBySkus(any(), any()))
                .thenReturn(List.of(row(UUID.randomUUID(), "111"), row(UUID.randomUUID(), "222")));
        when(queue.enqueue(any())).thenReturn(1);

        LabelCardsResponse body = (LabelCardsResponse) controller.labelCards(
                new LabelCardsRequest(List.of("111", "222"), "ozon")).getBody();

        assertThat(body.found()).isEqualTo(2);
        assertThat(body.accepted()).isEqualTo(1);
        assertThat(body.missing()).isEmpty();
    }

    @Test
    void labelCardsRejectsEmptyList() {
        for (List<String> skus : Arrays.asList(null, List.<String>of(), Arrays.asList(" ", null))) {
            ResponseEntity<?> response = controller.labelCards(new LabelCardsRequest(skus, "ozon"));
            assertThat(status(response)).isEqualTo(400);
            assertThat(error(response)).isEqualTo("Список sku пуст");
        }
        verifyNoInteractions(cardRepository, queue);
    }

    @Test
    void labelCardsRejectsTooManySkus() {
        List<String> skus = new ArrayList<>();
        for (int i = 0; i <= ProdSpecsControllerLimits.MAX_SKUS; i++) {
            skus.add("sku" + i);
        }

        ResponseEntity<?> response = controller.labelCards(new LabelCardsRequest(skus, "ozon"));

        assertThat(status(response)).isEqualTo(400);
        verifyNoInteractions(cardRepository, queue);
    }

    @Test
    void labelCardsRejectsMissingMarketplace() {
        for (String marketplace : Arrays.asList(null, "", "  ")) {
            ResponseEntity<?> response = controller.labelCards(
                    new LabelCardsRequest(List.of("111"), marketplace));
            assertThat(status(response)).isEqualTo(400);
            assertThat(error(response)).isEqualTo("Не указан маркетплейс");
        }
        verifyNoInteractions(cardRepository, queue);
    }

    // ---------- Вызов модели ----------

    @Test
    void llmCallsGatewayWithAnalysisModel() {
        when(gateway.call(any(), any(), any(), any())).thenReturn(
                new LlmResponse("ответ", "qwen3.6-35b-a3b", 1500, 1200, 300));

        ResponseEntity<?> response = controller.llm(
                llmRequest("prod_specs_analysis", "система", "данные", null));

        assertThat(status(response)).isEqualTo(200);
        LlmCallResponse body = (LlmCallResponse) response.getBody();
        assertThat(body.content()).isEqualTo("ответ");
        assertThat(body.model()).isEqualTo("qwen3.6-35b-a3b");
        assertThat(body.tokens()).isEqualTo(1500);
        assertThat(body.promptTokens()).isEqualTo(1200);
        assertThat(body.completionTokens()).isEqualTo(300);

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).call(eq("prod_specs_analysis"), eq("stratum"), eq("s-1"), request.capture());
        assertThat(request.getValue().model()).isEqualTo("qwen-test");
        assertThat(request.getValue().systemPrompt()).isEqualTo("система");
        assertThat(request.getValue().userPrompt()).isEqualTo("данные");
        assertThat(request.getValue().maxTokens()).isEqualTo(4000);
        assertThat(request.getValue().imageUrl()).isNull();
    }

    @Test
    void llmUsesGivenMaxTokens() {
        when(gateway.call(any(), any(), any(), any())).thenReturn(new LlmResponse("ок", "m", 10));

        controller.llm(llmRequest("prod_specs_analysis", "система", "данные", 2500));

        ArgumentCaptor<LlmRequest> request = ArgumentCaptor.forClass(LlmRequest.class);
        verify(gateway).call(any(), any(), any(), request.capture());
        assertThat(request.getValue().maxTokens()).isEqualTo(2500);
    }

    @Test
    void llmRejectsOtherPurposes() {
        for (String purpose : Arrays.asList("niche_query", "prod_specs_card", "что-то", null)) {
            ResponseEntity<?> response = controller.llm(llmRequest(purpose, "система", "данные", null));
            assertThat(status(response)).isEqualTo(400);
        }
        verifyNoInteractions(gateway);
    }

    @Test
    void llmRejectsBlankPrompts() {
        assertThat(status(controller.llm(llmRequest("prod_specs_analysis", " ", "данные", null)))).isEqualTo(400);
        assertThat(status(controller.llm(llmRequest("prod_specs_analysis", "система", null, null)))).isEqualTo(400);
        verifyNoInteractions(gateway);
    }

    @Test
    void llmRejectsMaxTokensOutOfRange() {
        assertThat(status(controller.llm(llmRequest("prod_specs_analysis", "с", "д", 0)))).isEqualTo(400);
        assertThat(status(controller.llm(llmRequest("prod_specs_analysis", "с", "д",
                ProdSpecsControllerLimits.MAX_LLM_TOKENS + 1)))).isEqualTo(400);
        verify(gateway, never()).call(any(), any(), any(), any());
    }

    @Test
    void llmErrorsAreMappedToStatusCodes() {
        Map<LlmException.Kind, Integer> expected = Map.of(
                LlmException.Kind.AUTH, 502,
                LlmException.Kind.TIMEOUT, 504,
                LlmException.Kind.OTHER, 502);
        expected.forEach((kind, code) -> {
            doThrow(new LlmException(kind, "сбой")).when(gateway).call(any(), any(), any(), any());

            ResponseEntity<?> response = controller.llm(
                    llmRequest("prod_specs_analysis", "система", "данные", null));

            assertThat(status(response)).isEqualTo(code);
            assertThat(((Map<?, ?>) response.getBody()).get("kind")).isEqualTo(kind.name());
            assertThat(error(response).toString()).startsWith(kind + ": ");
        });
    }

    /** Пределы контроллера: значения продублированы в тесте, чтобы их смена была заметной. */
    private static final class ProdSpecsControllerLimits {
        static final int MAX_SKUS = 200;
        static final int MAX_LLM_TOKENS = 8000;
    }
}