package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.redis.SelectResultConsumer;
import com.mercator.taskmanager.redis.SelectResultMessage;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.FillBatchRegistry;
import com.mercator.taskmanager.service.NicheSelectionService;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Юнит-тест приёма подбора в SelectResultConsumer.handle():
 *  - лимит count страты (по действующим членам страты) — сверх count карточки не пишутся;
 *  - товар уже в сете (saveCard вернул false) не занимает место в лимите;
 *  - результат без stratum_id, не принадлежащий нише, пропускается.
 * Зависимости мокнуты (без Postgres/ClickHouse). NicheSelectionService —
 * мок: isSelectTask по умолчанию false, результат идёт веткой страты.
 */
class SelectResultConsumerLimitTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    private final StratumRepository stratumRepo = mock(StratumRepository.class);
    private final CardWriteService cardWriteService = mock(CardWriteService.class);
    private final FillBatchRegistry batchRegistry = mock(FillBatchRegistry.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final NicheSelectionService nicheSelection = mock(NicheSelectionService.class);
    private final SelectResultConsumer consumer = new SelectResultConsumer(
            stratumRepo, cardWriteService, batchRegistry, cardRepository, nicheSelection);

    private StratumEntity stratum(UUID id, String query, int count) {
        StratumEntity stratum = new StratumEntity();
        stratum.setId(id);
        stratum.setQuery(query);
        stratum.setCount(count);
        when(stratumRepo.findById(id)).thenReturn(Optional.of(stratum));
        return stratum;
    }

    @Test
    void limitCutsOverflow() {
        UUID stratumId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        StratumEntity stratum = stratum(stratumId, "Джинсы мужские", 2);   // план — 2 карточки
        when(cardRepository.countActiveStratumMembers(stratumId)).thenReturn(0L);
        when(cardWriteService.saveCard(any(), any(), any(), any())).thenReturn(true);

        consumer.handle(buildResultJson(setId, stratumId, 5));            // подбор вернул 5

        // Записаны только 2 из 5 — лимит сработал. Четвёртый аргумент — метка
        // парсера из результата (в тестовом JSON её нет, поэтому null).
        verify(cardWriteService, times(2))
                .saveCard(eq(stratum), eq("Санкт-Петербург"), any(OzonCard.class), isNull());
        verify(batchRegistry).recordResult(eq(setId), eq(2));
    }

    @Test
    void duplicateInSetDoesNotTakeLimit() {
        UUID stratumId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        stratum(stratumId, "Джинсы мужские", 2);
        when(cardRepository.countActiveStratumMembers(stratumId)).thenReturn(0L);
        // первая карточка — товар уже в сете, остальные записываются
        when(cardWriteService.saveCard(any(), any(), any(), any())).thenReturn(false, true, true);

        consumer.handle(buildResultJson(setId, stratumId, 4));

        verify(cardWriteService, times(3)).saveCard(any(), any(), any(OzonCard.class), any());
        verify(batchRegistry).recordResult(eq(setId), eq(2));
    }

    @Test
    void fullStratumDropsWholeResult() {
        UUID stratumId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();
        stratum(stratumId, "Носки мужские", 3);
        when(cardRepository.countActiveStratumMembers(stratumId)).thenReturn(3L);   // страта полная

        consumer.handle(buildResultJson(setId, stratumId, 4));              // подбор вернул 4

        // Ничего не записано — результат отброшен целиком.
        verify(cardWriteService, never()).saveCard(any(), anyString(), any(OzonCard.class), any());
    }

    @Test
    void resultWithoutStratumIsSkipped() {
        // Запоздавший ответ на заменённую задачу ниши: stratum_id пуст,
        // task_id нише уже не принадлежит (isSelectTask → false).
        consumer.handle(buildResultJson(null, null, 3));

        verify(stratumRepo, never()).findById(any());
        verify(cardWriteService, never()).saveCard(any(), anyString(), any(OzonCard.class), any());
        verify(batchRegistry, never()).recordResult(any(), anyInt());
    }

    // Собрать JSON результата подбора с нужным числом карточек.
    private String buildResultJson(UUID setId, UUID stratumId, int cardCount) {
        SelectResultMessage msg = new SelectResultMessage();
        msg.setTaskId(UUID.randomUUID());
        msg.setSetId(setId);
        msg.setStratumId(stratumId);
        msg.setGeo("Санкт-Петербург");
        msg.setOk(true);
        msg.setFoundCount(cardCount);

        List<OzonCard> cards = new ArrayList<>();
        for (int i = 0; i < cardCount; i++) {
            OzonCard c = new OzonCard();
            c.setSku("SKU" + i);
            c.setName("Карточка " + i);
            cards.add(c);
        }
        msg.setCards(cards);
        return mapper.writeValueAsString(msg);
    }
}