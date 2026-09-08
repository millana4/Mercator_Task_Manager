package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Сквозной тест Postgres: сохраняем цепочку сет → страта → карточка
 * и читаем обратно, проверяя, что связи сохранились.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostgresIntegrationTest extends PostgresTestBase {

    @Autowired SetRepository setRepository;
    @Autowired SetClothingRepository stratumRepository;
    @Autowired CardRepository cardRepository;

    @Test
    @Transactional
    void savesAndReadsFullChain() {
        // 1. Сет
        SetEntity set = new SetEntity();
        set.setMarketplace("ozon");
        set.setCategory("clothing");
        set.setGeo("Санкт-Петербург");
        // id и created_at проставит база (DEFAULT), поэтому их не задаём
        SetEntity savedSet = setRepository.save(set);
        assertNotNull(savedSet.getId(), "У сохранённого сета должен появиться id");

        // 2. Страта под этим сетом
        SetClothingEntity stratum = new SetClothingEntity();
        stratum.setSet(savedSet);
        stratum.setItem("Рубашка");
        stratum.setQuery("Рубашка женская");
        stratum.setCount(20);
        stratum.setGender("f");
        stratum.setLayer((short) 1);
        stratum.setIsSeasonal(true);
        stratum.setBaseShare(new BigDecimal("0.500"));
        SetClothingEntity savedStratum = stratumRepository.save(stratum);
        assertNotNull(savedStratum.getId());

        // 3. Карточка под этой стратой
        CardEntity card = new CardEntity();
        card.setStratum(savedStratum);
        card.setSku("3974473087");
        card.setName("Рубашка в полоску");
        card.setSellerId("3463754");
        card.setCollection("base");
        card.setStatus("active");
        card.setFailedAttempts(0);
        cardRepository.save(card);

        // 4. Читаем обратно и проверяем связи
        List<CardEntity> cards = cardRepository.findByStratumId(savedStratum.getId());
        assertEquals(1, cards.size());

        CardEntity read = cards.get(0);
        assertEquals("3974473087", read.getSku());
        assertEquals("Рубашка", read.getStratum().getItem());          // карточка → страта
        assertEquals("Санкт-Петербург", read.getStratum().getSet().getGeo()); // страта → сет
    }
}