package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import com.mercator.taskmanager.service.CardContentBackfillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Добор content_txt: задачи обхода с пометкой full_content и отметка last_enqueued_at. */
class CardContentBackfillServiceTest {

    private static final short OZON_ID = 1;

    private CardRepository cardRepository;
    private ParseTaskProducer producer;
    private CardContentBackfillService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CardRepository.class);
        producer = mock(ParseTaskProducer.class);
        MarketplaceRepository marketplaceRepository = mock(MarketplaceRepository.class);

        MarketplaceEntity ozon = mock(MarketplaceEntity.class);
        when(ozon.getId()).thenReturn(OZON_ID);
        when(ozon.getTitle()).thenReturn("ozon");
        when(marketplaceRepository.findAll()).thenReturn(List.of(ozon));

        service = new CardContentBackfillService(cardRepository, marketplaceRepository,
                producer, "Санкт-Петербург", 45);
    }

    @Test
    void enqueuesFullContentTasksAndMarksCards() {
        CardEntity first = card("4569427009");
        CardEntity second = card("3423134714");
        when(cardRepository.findContentBackfillCandidates(eq(OZON_ID), any(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(first, second));

        assertEquals(2, service.enqueue(null));

        ArgumentCaptor<ParseTaskMessage> tasks = ArgumentCaptor.forClass(ParseTaskMessage.class);
        verify(producer, times(2)).send(tasks.capture());
        for (ParseTaskMessage task : tasks.getAllValues()) {
            assertTrue(task.getFullContent());
            assertEquals("ozon", task.getMarketplace());
            assertEquals("Санкт-Петербург", task.getGeo());
        }
        assertEquals(first.getId(), tasks.getAllValues().get(0).getCardId());
        assertEquals("3423134714", tasks.getAllValues().get(1).getSku());
        verify(cardRepository).markEnqueued(eq(List.of(first.getId(), second.getId())), any());
    }

    @Test
    void limitIsPassedToQuery() {
        when(cardRepository.findContentBackfillCandidates(eq(OZON_ID), any(), anyInt()))
                .thenReturn(List.of());

        service.enqueue(5);

        verify(cardRepository).findContentBackfillCandidates(eq(OZON_ID), any(), eq(5));
    }

    @Test
    void nothingToEnqueue() {
        when(cardRepository.findContentBackfillCandidates(eq(OZON_ID), any(), anyInt()))
                .thenReturn(List.of());

        assertEquals(0, service.enqueue(null));

        verify(producer, never()).send(any());
        verify(cardRepository, never()).markEnqueued(any(), any());
    }

    private static CardEntity card(String sku) {
        CardEntity card = new CardEntity();
        card.setId(UUID.randomUUID());
        card.setSku(sku);
        return card;
    }
}