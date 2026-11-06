package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.redis.ParseResultMessage;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.repository.NicheRepository;
import com.mercator.taskmanager.service.NicheReferenceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Референс ниши: задача с полным разбором; недоступный референс — ниша failed. */
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

    @Test
    void unavailableReferenceFailsEvenWithDescription() {
        NicheRepository repository = mock(NicheRepository.class);
        NicheReferenceService service =
                new NicheReferenceService(repository, mock(ParseTaskProducer.class));

        UUID taskId = UUID.randomUUID();
        NicheEntity niche = mock(NicheEntity.class);
        when(niche.getStatus()).thenReturn(NicheStatus.REFERENCE_PARSING);
        when(niche.getDescription()).thenReturn("розовое худи для девочки");
        when(repository.findByReferenceTaskId(taskId)).thenReturn(Optional.of(niche));

        OzonCard card = new OzonCard();
        card.setButtonState("not_found");
        ParseResultMessage result = new ParseResultMessage();
        result.setTaskId(taskId);
        result.setOk(true);
        result.setCard(card);

        service.handleResult(result);

        verify(niche).setStatus(NicheStatus.FAILED);
        verify(niche).setError("Референс недоступен: страница товара удалена");
        verify(niche, never()).setReferenceSnapshot(any());
    }
}