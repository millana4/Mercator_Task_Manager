package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.entity.LlmRequestLogEntity;
import com.mercator.taskmanager.llm.LlmException;
import com.mercator.taskmanager.llm.LlmGateway;
import com.mercator.taskmanager.llm.LlmRequest;
import com.mercator.taskmanager.llm.LlmResponse;
import com.mercator.taskmanager.llm.YandexLlmClient;
import com.mercator.taskmanager.repository.LlmRequestLogRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Шлюз к нейросети: каждое обращение (успешное и нет) попадает в журнал с моделью,
 * токенами, назначением и ссылкой; сбой журнала основной вызов не ломает.
 */
class LlmGatewayUnitTest {

    private static final LlmRequest REQUEST =
            LlmRequest.text("qwen3.6-35b-a3b", "система", "вопрос", 1500);

    private final YandexLlmClient client = mock(YandexLlmClient.class);
    private final LlmRequestLogRepository repository = mock(LlmRequestLogRepository.class);
    private final LlmGateway gateway = new LlmGateway(client, repository);

    private LlmRequestLogEntity saved() {
        ArgumentCaptor<LlmRequestLogEntity> captor = ArgumentCaptor.forClass(LlmRequestLogEntity.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void successIsLoggedWithTokens() {
        LlmResponse answer = new LlmResponse("ответ", "qwen3.6-35b-a3b", 1500, 1200, 300);
        when(client.complete(any())).thenReturn(answer);

        LlmResponse result = gateway.call(LlmGateway.PROD_SPECS_CARD, "sku", "123", REQUEST);

        assertThat(result).isSameAs(answer);
        LlmRequestLogEntity entry = saved();
        assertThat(entry.getPurpose()).isEqualTo("prod_specs_card");
        assertThat(entry.getModel()).isEqualTo("qwen3.6-35b-a3b");
        assertThat(entry.getPromptTokens()).isEqualTo(1200);
        assertThat(entry.getCompletionTokens()).isEqualTo(300);
        assertThat(entry.getTotalTokens()).isEqualTo(1500);
        assertThat(entry.getSuccess()).isTrue();
        assertThat(entry.getError()).isNull();
        assertThat(entry.getRefType()).isEqualTo("sku");
        assertThat(entry.getRefId()).isEqualTo("123");
        assertThat(entry.getDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void llmErrorIsLoggedAndRethrown() {
        when(client.complete(any())).thenThrow(
                new LlmException(LlmException.Kind.TIMEOUT, "таймаут 120 с"));

        assertThatThrownBy(() -> gateway.call(LlmGateway.PROD_SPECS_ANALYSIS, "stratum", "s1", REQUEST))
                .isInstanceOf(LlmException.class);

        LlmRequestLogEntity entry = saved();
        assertThat(entry.getSuccess()).isFalse();
        assertThat(entry.getError()).startsWith("TIMEOUT: ");
        assertThat(entry.getModel()).isEqualTo("qwen3.6-35b-a3b");  // модель запроса
        assertThat(entry.getTotalTokens()).isNull();
    }

    @Test
    void unexpectedErrorIsWrappedAndLogged() {
        when(client.complete(any())).thenThrow(new IllegalStateException("сбой"));

        assertThatThrownBy(() -> gateway.call(LlmGateway.NICHE_QUERY, "niche", "n1", REQUEST))
                .isInstanceOfSatisfying(LlmException.class,
                        e -> assertThat(e.getKind()).isEqualTo(LlmException.Kind.OTHER));

        assertThat(saved().getError()).startsWith("OTHER: ");
    }

    @Test
    void modelOfClientIsLoggedWhenRequestHasNone() {
        when(client.getModel()).thenReturn("deepseek-v4.1-flash");
        when(client.complete(any())).thenThrow(new LlmException(LlmException.Kind.AUTH, "нет ключей"));

        assertThatThrownBy(() -> gateway.call(LlmGateway.NICHE_QUERY, "niche", "n1",
                LlmRequest.text(null, "система", "вопрос", null)))
                .isInstanceOf(LlmException.class);

        assertThat(saved().getModel()).isEqualTo("deepseek-v4.1-flash");
    }

    @Test
    void journalFailureDoesNotBreakTheCall() {
        LlmResponse answer = new LlmResponse("ответ", "qwen3.6-35b-a3b", 10);
        when(client.complete(any())).thenReturn(answer);
        when(repository.save(any())).thenThrow(new RuntimeException("база недоступна"));

        assertThat(gateway.call(LlmGateway.PROD_SPECS_CARD, "sku", "123", REQUEST)).isSameAs(answer);
    }

    @Test
    void knownPurposes() {
        assertThat(LlmGateway.isKnownPurpose("niche_query")).isTrue();
        assertThat(LlmGateway.isKnownPurpose("prod_specs_card")).isTrue();
        assertThat(LlmGateway.isKnownPurpose("prod_specs_analysis")).isTrue();
        assertThat(LlmGateway.isKnownPurpose("что-то другое")).isFalse();
        assertThat(LlmGateway.isKnownPurpose(null)).isFalse();
    }
}