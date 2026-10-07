package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.clickhouse.Measurement;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.JsonHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Запись карточки:
 *  - первый замер новой карточки отмечается в last_measured_at тем же временем,
 *    что parsed_at замера, — иначе обход сразу отправит карточку на повторный парсинг;
 *  - при полном разборе (full_content) карточка целиком пишется в content_txt
 *    с ключами в snake_case, время разбора — в content_txt_parsed_at, ТН ВЭД — в tnved.
 */
class CardWriteServiceUnitTest {

    private CardRepository cardRepository;
    private MeasurementRepository measurementRepository;
    private CardWriteService service;

    @BeforeEach
    void setUp() {
        cardRepository = mock(CardRepository.class);
        measurementRepository = mock(MeasurementRepository.class);
        service = new CardWriteService(cardRepository, measurementRepository,
                mock(CardSnapshotRepository.class), new JsonHelper());
        // save возвращает ту же сущность, как это делает JPA для новой записи
        when(cardRepository.save(any(CardEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ---------- last_measured_at ----------

    @Test
    void stratumCardGetsLastMeasuredAtEqualToFirstMeasurement() {
        service.saveCard(stratum(), "Санкт-Петербург", mock(OzonCard.class), "1");

        assertFirstMeasurementMarked();
    }

    @Test
    void nicheCardGetsLastMeasuredAtEqualToFirstMeasurement() {
        service.saveNicheCard(mock(NicheEntity.class), "Санкт-Петербург", mock(OzonCard.class), "1");

        assertFirstMeasurementMarked();
    }

    @Test
    void writeMeasurementForExistingCardDoesNotTouchPostgres() {
        service.writeMeasurementAndSnapshot(UUID.randomUUID(), "Санкт-Петербург",
                mock(OzonCard.class), "1");

        verify(measurementRepository).insert(any(Measurement.class));
        verifyNoInteractions(cardRepository);
    }

    // ---------- Полный набор при создании карточки ----------

    @Test
    void newCardWithFullContentGetsContent() {
        service.saveCard(stratum(), "Санкт-Петербург", fullCard("6109100000"), "6");

        CardEntity saved = savedCard();
        Map<String, Object> content = saved.getContentTxt();
        assertNotNull(content);
        assertEquals(true, content.get("full_content"));
        assertEquals("Лонгслив", ((Map<?, ?>) content.get("characteristics_full")).get("Тип"));
        assertFalse(content.containsKey("characteristicsFull"));
        assertEquals(saved.getLastMeasuredAt(), saved.getContentTxtParsedAt());
        assertEquals("6109100000", saved.getTnved());
    }

    @Test
    void newNicheCardWithFullContentGetsContent() {
        service.saveNicheCard(mock(NicheEntity.class), "Санкт-Петербург", fullCard(null), "6");

        CardEntity saved = savedCard();
        assertNotNull(saved.getContentTxt());
        assertNotNull(saved.getContentTxtParsedAt());
        assertNull(saved.getTnved());
    }

    @Test
    void newCardWithoutFullContentHasNoContent() {
        OzonCard card = fullCard("6109100000");
        card.setFullContent(null);   // парсер старой версии

        service.saveCard(stratum(), "Санкт-Петербург", card, "1");

        CardEntity saved = savedCard();
        assertNull(saved.getContentTxt());
        assertNull(saved.getContentTxtParsedAt());
        assertNull(saved.getTnved());
    }

    // ---------- Полный набор существующей карточки ----------

    @Test
    void writeFullContentUpdatesExistingCard() {
        UUID cardId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();

        assertTrue(service.writeFullContent(cardId, fullCard("6109100000"), at));

        verify(cardRepository).updateContent(eq(cardId), contains("\"characteristics_full\""),
                eq(at), eq("6109100000"));
    }

    @Test
    void writeFullContentPassesBlankTnvedAsNull() {
        UUID cardId = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now();

        service.writeFullContent(cardId, fullCard("  "), at);

        verify(cardRepository).updateContent(eq(cardId), any(String.class), eq(at), isNull());
    }

    @Test
    void writeFullContentSkipsWithoutFlag() {
        OzonCard card = fullCard("6109100000");
        card.setFullContent(false);

        assertFalse(service.writeFullContent(UUID.randomUUID(), card, OffsetDateTime.now()));

        verifyNoInteractions(cardRepository);
    }


    // ---------- Кандидат ниши ----------

    @Test
    void nicheCandidateIsSavedWithCandidateStatus() {
        service.saveNicheCandidate(mock(NicheEntity.class), "Санкт-Петербург", fullCard(null), "6");

        CardEntity saved = savedCard();
        assertEquals("candidate", saved.getStatus());
        assertNotNull(saved.getContentTxt());
        assertNotNull(saved.getLastMeasuredAt());
        verify(measurementRepository).insert(any(Measurement.class));
    }


    // ---------- Вспомогательное ----------

    private static StratumEntity stratum() {
        StratumEntity stratum = mock(StratumEntity.class, RETURNS_DEEP_STUBS);
        when(stratum.getSubcategory().getSet().getId()).thenReturn(UUID.randomUUID());
        return stratum;
    }

    private static OzonCard fullCard(String tnved) {
        OzonCard card = new OzonCard();
        card.setSku("4569427009");
        card.setName("Лонгслив Happyfox для девочек 1 шт");
        card.setCharacteristics(Map.of("Сезон", "Демисезон"));
        card.setCharacteristicsFull(Map.of("Тип", "Лонгслив", "Покрой", "облегающий"));
        card.setTnved(tnved);
        card.setFullContent(true);
        return card;
    }

    private CardEntity savedCard() {
        ArgumentCaptor<CardEntity> captor = ArgumentCaptor.forClass(CardEntity.class);
        verify(cardRepository).save(captor.capture());
        return captor.getValue();
    }

    private void assertFirstMeasurementMarked() {
        ArgumentCaptor<CardEntity> savedCard = ArgumentCaptor.forClass(CardEntity.class);
        ArgumentCaptor<Measurement> measurement = ArgumentCaptor.forClass(Measurement.class);
        verify(cardRepository).save(savedCard.capture());
        verify(measurementRepository).insert(measurement.capture());

        assertNotNull(savedCard.getValue().getLastMeasuredAt());
        assertEquals(measurement.getValue().getParsedAt(), savedCard.getValue().getLastMeasuredAt());
    }
}