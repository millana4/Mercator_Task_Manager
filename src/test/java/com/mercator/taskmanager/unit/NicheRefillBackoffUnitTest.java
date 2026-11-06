package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.GeoEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.redis.SelectResultMessage;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.NicheRankingService;
import com.mercator.taskmanager.service.NicheRankingStore;
import com.mercator.taskmanager.service.NicheRefillService;
import com.mercator.taskmanager.service.NicheSelectionService;
import com.mercator.taskmanager.service.NicheRefillServiceAccess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Автодобор ниш: пауза после поисков без новых кандидатов, выбор только по поводу. */
class NicheRefillBackoffUnitTest {

    private final NicheRepository nicheRepository = mock(NicheRepository.class);
    private final NicheCandidateRepository candidateRepository = mock(NicheCandidateRepository.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final NicheSelectionService selectionService = mock(NicheSelectionService.class);
    private final NicheRankingService rankingService = mock(NicheRankingService.class);
    private final NicheRankingStore store = mock(NicheRankingStore.class);

    private final UUID nicheId = UUID.randomUUID();
    private NicheEntity niche;
    private NicheRefillService refill;

    @BeforeEach
    void setUp() {
        niche = new NicheEntity();
        niche.setId(nicheId);
        niche.setStatus(NicheStatus.ACTIVE);
        niche.setPlanCount(34);
        when(nicheRepository.findByStatus(NicheStatus.ACTIVE)).thenReturn(List.of(niche));
        when(cardRepository.countActiveInNiche(nicheId)).thenReturn(12L);
        refill = new NicheRefillService(nicheRepository, candidateRepository, cardRepository,
                selectionService, rankingService, store, 2, 6, "24,72", 3, 45);
    }

    // ---------- Интервал между поисками ----------
    @Test
    void intervalGrowsWithEmptySearches() {
        long[] backoff = {24, 72};
        assertThat(NicheRefillServiceAccess.interval(0, 6, backoff)).isEqualTo(6);
        assertThat(NicheRefillServiceAccess.interval(1, 6, backoff)).isEqualTo(24);
        assertThat(NicheRefillServiceAccess.interval(2, 6, backoff)).isEqualTo(72);
        assertThat(NicheRefillServiceAccess.interval(7, 6, backoff)).isEqualTo(72);
        assertThat(NicheRefillServiceAccess.interval(3, 6, new long[0])).isEqualTo(6);
    }

    @Test
    void parsesBackoffHours() {
        assertThat(NicheRefillServiceAccess.parse("24, 72")).containsExactly(24, 72);
        assertThat(NicheRefillServiceAccess.parse("")).isEmpty();
    }

    @Test
    void exhaustedNicheWaitsLonger() {
        niche.setRefillEmptyCount(2);                                  // интервал 72 ч
        niche.setRefillRequestedAt(OffsetDateTime.now().minusHours(30));
        refill.startSearches();
        verify(selectionService, never()).sendRefillTask(any(), anyInt());

        niche.setRefillRequestedAt(OffsetDateTime.now().minusHours(73));
        refill.startSearches();
        verify(selectionService).sendRefillTask(niche, 44);            // дефицит 22 x 2
    }

    @Test
    void normalNicheSearchesEverySixHours() {
        niche.setRefillEmptyCount(0);
        niche.setRefillRequestedAt(OffsetDateTime.now().minusHours(7));
        refill.startSearches();
        verify(selectionService).sendRefillTask(niche, 44);
    }

    // ---------- Выбор только по поводу ----------
    @Test
    void noChoiceWithoutPendingFlag() {
        niche.setRefillRequestedAt(OffsetDateTime.now().minusHours(1));
        niche.setRefillChoicePending(false);
        refill.fillDeficits();
        verify(rankingService, never()).score(any());
    }

    @Test
    void droppedMembersMakeChoicePending() {
        niche.setRefillRequestedAt(OffsetDateTime.now().minusHours(1));
        when(store.cleanupDroppedMembers(nicheId)).thenReturn(1);
        // референса нет - выбирать не из чего: флаг ставится и сразу снимается, ранжирования нет
        refill.fillDeficits();
        verify(store).setRefillChoicePending(nicheId, true);
        verify(store).setRefillChoicePending(nicheId, false);
        verify(rankingService, never()).score(any());
    }

    // ---------- Результат поиска: счётчик и флаг ----------
    @Test
    void emptySearchIncrementsCounterAndNewCandidatesReset() {
        NicheSelectionService selection = new NicheSelectionService(nicheRepository,
                candidateRepository, cardRepository, mock(CardWriteService.class),
                mock(SelectTaskProducer.class), 10, 30);
        GeoEntity geo = mock(GeoEntity.class);
        when(geo.getTitle()).thenReturn("Санкт-Петербург");
        MarketplaceEntity marketplace = mock(MarketplaceEntity.class);
        when(marketplace.getId()).thenReturn((short) 1);
        niche.setGeo(geo);
        niche.setMarketplace(marketplace);

        UUID task1 = UUID.randomUUID();
        niche.setSelectTaskId(task1);
        when(nicheRepository.findBySelectTaskId(task1)).thenReturn(Optional.of(niche));
        selection.handleResult(result(task1, List.of()));
        assertThat(niche.getRefillEmptyCount()).isEqualTo(1);
        assertThat(niche.isRefillChoicePending()).isFalse();
        assertThat(niche.getSelectTaskId()).isNull();

        UUID task2 = UUID.randomUUID();
        niche.setSelectTaskId(task2);
        when(nicheRepository.findBySelectTaskId(task2)).thenReturn(Optional.of(niche));
        OzonCard card = new OzonCard();
        card.setSku("123456");
        selection.handleResult(result(task2, List.of(card)));
        assertThat(niche.getRefillEmptyCount()).isZero();
        assertThat(niche.isRefillChoicePending()).isTrue();
    }

    private static SelectResultMessage result(UUID taskId, List<OzonCard> cards) {
        SelectResultMessage r = new SelectResultMessage();
        r.setTaskId(taskId);
        r.setOk(true);
        r.setFoundCount(cards.size());
        r.setCards(cards);
        return r;
    }
}