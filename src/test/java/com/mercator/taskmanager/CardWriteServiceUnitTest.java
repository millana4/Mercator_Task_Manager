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

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Первый замер новой карточки отмечается в last_measured_at тем же временем,
 * что parsed_at замера, - иначе обход сразу отправит карточку на повторный парсинг.
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
                mock(CardSnapshotRepository.class), mock(JsonHelper.class));
        // save возвращает ту же сущность, как это делает JPA для новой записи
        when(cardRepository.save(any(CardEntity.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void stratumCardGetsLastMeasuredAtEqualToFirstMeasurement() {
        StratumEntity stratum = mock(StratumEntity.class, RETURNS_DEEP_STUBS);
        when(stratum.getSubcategory().getSet().getId()).thenReturn(UUID.randomUUID());

        service.saveCard(stratum, "Санкт-Петербург", mock(OzonCard.class), "1");

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

    private void assertFirstMeasurementMarked() {
        ArgumentCaptor<CardEntity> savedCard = ArgumentCaptor.forClass(CardEntity.class);
        ArgumentCaptor<Measurement> measurement = ArgumentCaptor.forClass(Measurement.class);
        verify(cardRepository).save(savedCard.capture());
        verify(measurementRepository).insert(measurement.capture());

        assertNotNull(savedCard.getValue().getLastMeasuredAt());
        assertEquals(measurement.getValue().getParsedAt(), savedCard.getValue().getLastMeasuredAt());
    }
}