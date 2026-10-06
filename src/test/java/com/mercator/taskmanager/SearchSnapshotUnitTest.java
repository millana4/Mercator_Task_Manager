package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.SearchSnapshotRepository;
import com.mercator.taskmanager.clickhouse.SearchSnapshotRow;
import com.mercator.taskmanager.redis.SearchResultConsumer;
import com.mercator.taskmanager.service.SearchSnapshotScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Расписание снимков выдачи и запись результата в строки search_snapshot. */
class SearchSnapshotUnitTest {

    @Test
    void parsesScheduleByCategory() {
        Map<String, Set<LocalTime>> s =
                SearchSnapshotScheduler.parseSchedule("kids_clothing=06:37,18:37;clothing=18:37");
        assertThat(s.get("kids_clothing")).containsExactly(LocalTime.of(6, 37), LocalTime.of(18, 37));
        assertThat(s.get("clothing")).containsExactly(LocalTime.of(18, 37));
        assertThat(SearchSnapshotScheduler.parseSchedule("")).isEmpty();
        assertThatThrownBy(() -> SearchSnapshotScheduler.parseSchedule("clothing:18:37"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesTilesWithRunInfo() {
        SearchSnapshotRepository repo = mock(SearchSnapshotRepository.class);
        new SearchResultConsumer(repo).handle("""
                {"task_id":"11111111-1111-1111-1111-111111111111",
                 "set_id":"22222222-2222-2222-2222-222222222222",
                 "stratum_id":"33333333-3333-3333-3333-333333333333",
                 "geo":"Санкт-Петербург","marketplace":"Ozon","category":"kids_clothing",
                 "query":"Блузка для девочки","run_at":"2026-10-06T18:37:00+03:00",
                 "parsed_at":"2026-10-06T15:40:12+00:00","parser_id":"1","ok":true,
                 "scrolls":1,"tiles_total":1,"error":null,
                 "tiles":[{"position":1,"sku":"5369938708","name":"Блузка Winkid",
                           "brand":null,"brand_verified":false,"price":1075.0,
                           "original_price":null,"discount_pct":null,"quantity":3,
                           "rating":4.9,"reviews":821,"badges":["Цена что надо"],
                           "labels":["Стало дешевле"]}]}
                """);
        ArgumentCaptor<List<SearchSnapshotRow>> cap = ArgumentCaptor.forClass(List.class);
        verify(repo).insertTiles(cap.capture());
        SearchSnapshotRow r = cap.getValue().get(0);
        assertThat(r.runAt()).isEqualTo(OffsetDateTime.parse("2026-10-06T18:37:00+03:00"));
        assertThat(r.category()).isEqualTo("kids_clothing");
        assertThat(r.position()).isEqualTo(1);
        assertThat(r.sku()).isEqualTo("5369938708");
        assertThat(r.brand()).isEqualTo("");
        assertThat(r.quantity()).isEqualTo(3);
        assertThat(r.badges()).containsExactly("Цена что надо");
        assertThat(r.labels()).containsExactly("Стало дешевле");
        assertThat(r.parserId()).isEqualTo("1");
    }

    @Test
    void failedSnapshotIsNotWritten() {
        SearchSnapshotRepository repo = mock(SearchSnapshotRepository.class);
        new SearchResultConsumer(repo).handle("""
                {"task_id":"11111111-1111-1111-1111-111111111111","ok":false,
                 "error":"Антибот заблокировал","error_code":"antibot_blocked","tiles":[]}
                """);
        verify(repo, never()).insertTiles(any());
    }
}