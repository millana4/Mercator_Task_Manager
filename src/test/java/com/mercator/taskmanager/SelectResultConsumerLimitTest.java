package com.mercator.taskmanager;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.SetClothingEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.redis.SelectResultConsumer;
import com.mercator.taskmanager.redis.SelectResultMessage;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetClothingRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.FillBatchRegistry;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Юнит-тест лимита count при приёме подбора. Проверяет отсечение в
 * SelectResultConsumer.handle(): сверх count страты карточки не пишутся.
 * Зависимости мокнуты (без Postgres/ClickHouse) — тест изолирует ровно
 * логику лимита.
 */
class SelectResultConsumerLimitTest {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void limitCutsOverflow() throws Exception {
        SetClothingRepository stratumRepo = mock(SetClothingRepository.class);
        CardWriteService cardWriteService = mock(CardWriteService.class);
        FillBatchRegistry batchRegistry = mock(FillBatchRegistry.class);
        CardRepository cardRepository = mock(CardRepository.class);

        SelectResultConsumer consumer = new SelectResultConsumer(
                stratumRepo, cardWriteService, batchRegistry, cardRepository);

        UUID stratumId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();

        SetClothingEntity stratum = new SetClothingEntity();
        stratum.setId(stratumId);
        stratum.setQuery("Джинсы мужские");
        stratum.setCount(2);                         // план — 2 карточки

        when(stratumRepo.findById(stratumId)).thenReturn(Optional.of(stratum));
        when(cardRepository.countByStratumIdAndStatus(stratumId, "active"))
                .thenReturn(0);                      // активных пока нет

        String json = buildResultJson(setId, stratumId, 5);  // подбор вернул 5

        consumer.handle(json);

        // Записаны только 2 из 5 — лимит сработал.
        verify(cardWriteService, times(2))
                .saveCard(eq(stratum), eq("Санкт-Петербург"), any(OzonCard.class));
        verify(batchRegistry).recordResult(eq(setId), eq(2));
    }

    @Test
    void fullStratumDropsWholeResult() throws Exception {
        SetClothingRepository stratumRepo = mock(SetClothingRepository.class);
        CardWriteService cardWriteService = mock(CardWriteService.class);
        FillBatchRegistry batchRegistry = mock(FillBatchRegistry.class);
        CardRepository cardRepository = mock(CardRepository.class);

        SelectResultConsumer consumer = new SelectResultConsumer(
                stratumRepo, cardWriteService, batchRegistry, cardRepository);

        UUID stratumId = UUID.randomUUID();
        UUID setId = UUID.randomUUID();

        SetClothingEntity stratum = new SetClothingEntity();
        stratum.setId(stratumId);
        stratum.setQuery("Носки мужские");
        stratum.setCount(3);

        when(stratumRepo.findById(stratumId)).thenReturn(Optional.of(stratum));
        when(cardRepository.countByStratumIdAndStatus(stratumId, "active"))
                .thenReturn(3);                      // страта уже полная

        String json = buildResultJson(setId, stratumId, 4);  // подбор вернул 4

        consumer.handle(json);

        // Ничего не записано — результат отброшен целиком.
        verify(cardWriteService, never())
                .saveCard(any(), anyString(), any(OzonCard.class));
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