package com.mercator.taskmanager;

import com.mercator.taskmanager.clickhouse.SearchSnapshotRepository;
import com.mercator.taskmanager.contract.Ozon.ExcludedCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.CategoryEntity;
import com.mercator.taskmanager.entity.GeoEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.redis.PrioritySku;
import com.mercator.taskmanager.redis.SelectTaskMessage;
import com.mercator.taskmanager.redis.SelectTaskProducer;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.FillBatchRegistry;
import com.mercator.taskmanager.service.SetFillRedisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Список частых SKU поисковой выдачи в задаче подбора страты. */
class SetFillPriorityUnitTest {

    private final SetRepository setRepository = mock(SetRepository.class);
    private final StratumRepository stratumRepository = mock(StratumRepository.class);
    private final SelectTaskProducer producer = mock(SelectTaskProducer.class);
    private final FillBatchRegistry batchRegistry = mock(FillBatchRegistry.class);
    private final CardRepository cardRepository = mock(CardRepository.class);
    private final SearchSnapshotRepository snapshots = mock(SearchSnapshotRepository.class);

    private final UUID setId = UUID.randomUUID();
    private final UUID stratumId = UUID.randomUUID();

    private SetFillRedisService service;

    @BeforeEach
    void setUp() {
        GeoEntity geo = mock(GeoEntity.class);
        when(geo.getTitle()).thenReturn("Санкт-Петербург");
        MarketplaceEntity marketplace = mock(MarketplaceEntity.class);
        when(marketplace.getTitle()).thenReturn("ozon");
        when(marketplace.getId()).thenReturn((short) 1);
        CategoryEntity category = mock(CategoryEntity.class);
        when(category.getTitle()).thenReturn("kids_clothing");

        SetEntity set = mock(SetEntity.class);
        when(set.getId()).thenReturn(setId);
        when(set.getGeo()).thenReturn(geo);
        when(set.getMarketplace()).thenReturn(marketplace);
        when(set.getCategory()).thenReturn(category);

        SubcategoryEntity subcategory = mock(SubcategoryEntity.class);
        when(subcategory.getSet()).thenReturn(set);

        StratumEntity stratum = mock(StratumEntity.class);
        when(stratum.getId()).thenReturn(stratumId);
        when(stratum.getSubcategory()).thenReturn(subcategory);
        when(stratum.getQuery()).thenReturn("Блузка для девочки");
        when(stratum.getCount()).thenReturn(3);

        // Карточка «A» уже в сете — попадёт в exclude.
        CardEntity taken = mock(CardEntity.class);
        when(taken.getSku()).thenReturn("A");

        when(setRepository.findAll()).thenReturn(List.of(set));
        when(setRepository.findById(setId)).thenReturn(Optional.of(set));
        when(stratumRepository.findBySubcategorySetId(setId)).thenReturn(List.of(stratum));
        when(stratumRepository.findById(stratumId)).thenReturn(Optional.of(stratum));
        when(cardRepository.findByStratumId(stratumId)).thenReturn(List.of(taken));

        service = new SetFillRedisService(setRepository, stratumRepository, producer,
                batchRegistry, cardRepository, snapshots, Duration.ofHours(4), 7);
    }

    @Test
    void refillSendsFrequentSkusWithoutExcluded() {
        when(snapshots.findSkuFrequency(stratumId, 7)).thenReturn(List.of(
                new PrioritySku("A", 7), new PrioritySku("B", 6), new PrioritySku("C", 2)));

        assertThat(service.refillDeficits()).isEqualTo(1);

        SelectTaskMessage task = sentTask();
        assertThat(task.getPrioritySkus())
                .containsExactly(new PrioritySku("B", 6), new PrioritySku("C", 2));
        assertThat(task.getExclude()).extracting(ExcludedCard::getSku).containsExactly("A");
    }

    @Test
    void startFillSendsFrequentSkus() {
        when(snapshots.findSkuFrequency(stratumId, 7)).thenReturn(List.of(new PrioritySku("B", 3)));

        assertThat(service.startFill(setId)).isEqualTo(1);

        assertThat(sentTask().getPrioritySkus()).containsExactly(new PrioritySku("B", 3));
    }

    @Test
    void clickHouseFailureGivesEmptyPriority() {
        when(snapshots.findSkuFrequency(stratumId, 7))
                .thenThrow(new DataAccessResourceFailureException("нет связи"));

        assertThat(service.refillDeficits()).isEqualTo(1);

        assertThat(sentTask().getPrioritySkus()).isEmpty();
    }

    @Test
    void prioritySkusSerializedSnakeCase() {
        SelectTaskMessage task = new SelectTaskMessage();
        task.setPrioritySkus(List.of(new PrioritySku("B", 6)));

        String json = JsonMapper.builder()
                .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .build()
                .writeValueAsString(task);

        assertThat(json).contains("\"priority_skus\":[").contains("\"sku\":\"B\"").contains("\"count\":6");
    }

    private SelectTaskMessage sentTask() {
        ArgumentCaptor<SelectTaskMessage> cap = ArgumentCaptor.forClass(SelectTaskMessage.class);
        verify(producer).send(cap.capture());
        return cap.getValue();
    }
}