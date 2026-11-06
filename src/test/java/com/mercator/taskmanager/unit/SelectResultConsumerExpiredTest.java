package com.mercator.taskmanager.unit;

import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.redis.SelectResultConsumer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.FillBatchRegistry;
import com.mercator.taskmanager.service.NicheSelectionService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ответ парсера «задача устарела»: страта освобождается для повторной отправки. */
class SelectResultConsumerExpiredTest {

    private final StratumRepository stratumRepo = mock(StratumRepository.class);
    private final SelectResultConsumer consumer = new SelectResultConsumer(
            stratumRepo, mock(CardWriteService.class), mock(FillBatchRegistry.class),
            mock(CardRepository.class), mock(NicheSelectionService.class));

    private StratumEntity stratum() {
        StratumEntity stratum = new StratumEntity();
        stratum.setId(UUID.randomUUID());
        stratum.setQuery("Юбка для девочки");
        stratum.setSelectRequestedAt(OffsetDateTime.now().minusHours(7));
        when(stratumRepo.findById(stratum.getId())).thenReturn(Optional.of(stratum));
        return stratum;
    }

    private static String failed(UUID stratumId, String error) {
        return """
                {"task_id": "%s", "set_id": "%s", "stratum_id": "%s", "geo": "Санкт-Петербург",
                 "ok": false, "found_count": 0, "error": "%s"}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), stratumId, error);
    }

    @Test
    void expiredResultReleasesStratum() {
        StratumEntity stratum = stratum();

        consumer.handle(failed(stratum.getId(), "Задача подбора устарела: 7.0 ч от created_at"));

        assertThat(stratum.getSelectRequestedAt()).isNull();
        verify(stratumRepo).save(stratum);
    }

    @Test
    void otherErrorKeepsRetryTimer() {
        StratumEntity stratum = stratum();

        consumer.handle(failed(stratum.getId(), "Антибот заблокировал: Выдача заблокирована антиботом."));

        assertThat(stratum.getSelectRequestedAt()).isNotNull();
        verify(stratumRepo, never()).save(any());
    }
}