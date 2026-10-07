package com.mercator.taskmanager;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.contract.Ozon.OzonPrice;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheCandidateEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.redis.ParseResultMessage;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import com.mercator.taskmanager.service.CardLifecycleService;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.NicheVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Прошедший проверку кандидат ниши: полный набор пишется и существующей карточке. */
class NicheVerificationServiceTest {

    private static final UUID TASK_ID = UUID.randomUUID();

    private NicheCandidateRepository candidateRepository;
    private CardRepository cardRepository;
    private CardWriteService cardWriteService;
    private CardLifecycleService lifecycle;
    private NicheVerificationService service;
    private NicheEntity niche;

    @BeforeEach
    void setUp() {
        candidateRepository = mock(NicheCandidateRepository.class);
        cardRepository = mock(CardRepository.class);
        cardWriteService = mock(CardWriteService.class);
        lifecycle = mock(CardLifecycleService.class);
        service = new NicheVerificationService(candidateRepository, cardRepository,
                cardWriteService, lifecycle);

        niche = mock(NicheEntity.class, RETURNS_DEEP_STUBS);
        when(niche.getStatus()).thenReturn(NicheStatus.ACTIVE);
        when(niche.getId()).thenReturn(UUID.randomUUID());
        when(niche.getGeo().getTitle()).thenReturn("Санкт-Петербург");
    }

    @Test
    void returnedDroppedCardGetsFullContent() {
        CardEntity target = card("dropped");
        OzonCard card = passedCard();
        givenCandidate(target);

        service.handleResult(result(card));

        verify(lifecycle).markInStock(target.getId());
        verify(cardWriteService).writeFullContent(eq(target.getId()), eq(card), any());
    }

    @Test
    void activeCardGetsFullContent() {
        CardEntity target = card("active");
        OzonCard card = passedCard();
        givenCandidate(target);

        service.handleResult(result(card));

        verify(lifecycle, never()).markInStock(any());
        verify(cardWriteService).writeFullContent(eq(target.getId()), eq(card), any());
    }

    @Test
    void newCardIsCreatedWithoutSeparateContentWrite() {
        OzonCard card = passedCard();
        CardEntity created = card("active");
        NicheCandidateEntity candidate = givenCandidate(null);
        when(cardRepository.findBySkuAndMarketplace(any(), any())).thenReturn(Optional.empty());
        when(cardWriteService.saveNicheCard(eq(niche), eq("Санкт-Петербург"), eq(card), any()))
                .thenReturn(created);

        service.handleResult(result(card));

        verify(cardWriteService, never()).writeFullContent(any(), any(), any());
        verify(cardRepository).addToNiche(niche.getId(), created.getId());
        assertEquals("selected", candidate.getStatus());
    }

    @Test
    void candidateCardIsPromoted() {
        CardEntity target = card("candidate");
        givenCandidate(target);
        when(cardRepository.findBySkuAndMarketplace(any(), any())).thenReturn(Optional.of(target));

        service.handleResult(result(passedCard()));

        verify(lifecycle).markInStock(target.getId());
        verify(cardRepository).addToNiche(niche.getId(), target.getId());
    }

    @Test
    void activeCardPreferredOverCandidateCard() {
        CardEntity candidateCard = card("candidate");
        CardEntity activeCard = card("active");
        givenCandidate(candidateCard);
        when(cardRepository.findBySkuAndMarketplace(any(), any())).thenReturn(Optional.of(activeCard));

        service.handleResult(result(passedCard()));

        verify(lifecycle, never()).markInStock(any());
        verify(cardRepository).addToNiche(niche.getId(), activeCard.getId());
    }

    // ---------- Вспомогательное ----------

    private NicheCandidateEntity givenCandidate(CardEntity target) {
        NicheCandidateEntity candidate = new NicheCandidateEntity();
        candidate.setNiche(niche);
        candidate.setSku("4569427009");
        candidate.setCard(target);
        candidate.setVerifyTaskId(TASK_ID);
        when(candidateRepository.findByVerifyTaskId(TASK_ID)).thenReturn(Optional.of(candidate));
        return candidate;
    }

    private static CardEntity card(String status) {
        CardEntity card = new CardEntity();
        card.setId(UUID.randomUUID());
        card.setSku("4569427009");
        card.setStatus(status);
        return card;
    }

    // Карточка в наличии с ценой: подмена OzonPrice отдаёт 0.0 вместо null — цена есть
    private static OzonCard passedCard() {
        OzonCard card = new OzonCard();
        card.setSku("4569427009");
        card.setButtonState("in_cart");
        card.setPrice(mock(OzonPrice.class));
        card.setFullContent(true);
        return card;
    }

    private static ParseResultMessage result(OzonCard card) {
        ParseResultMessage result = new ParseResultMessage();
        result.setTaskId(TASK_ID);
        result.setOk(true);
        result.setParserId("6");
        result.setCard(card);
        return result;
    }
}