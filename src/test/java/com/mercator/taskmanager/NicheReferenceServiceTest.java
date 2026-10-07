package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.repository.NicheRepository;
import com.mercator.taskmanager.service.NicheReferenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Задача парсинга референса ниши — с пометкой полного разбора. */
class NicheReferenceServiceTest {

    @Test
    void referenceTaskRequestsFullContent() {
        ParseTaskProducer producer = mock(ParseTaskProducer.class);
        NicheReferenceService service =
                new NicheReferenceService(mock(NicheRepository.class), producer);

        NicheEntity niche = mock(NicheEntity.class, RETURNS_DEEP_STUBS);
        when(niche.getReferenceSku()).thenReturn("3423134714");
        when(niche.getGeo().getTitle()).thenReturn("Санкт-Петербург");
        when(niche.getMarketplace().getTitle()).thenReturn("ozon");

        service.sendReferenceTask(niche);

        ArgumentCaptor<ParseTaskMessage> task = ArgumentCaptor.forClass(ParseTaskMessage.class);
        verify(producer).send(task.capture());
        assertTrue(task.getValue().getFullContent());
        assertNull(task.getValue().getCardId());
        assertEquals("3423134714", task.getValue().getSku());
        assertEquals("ozon", task.getValue().getMarketplace());
    }
}