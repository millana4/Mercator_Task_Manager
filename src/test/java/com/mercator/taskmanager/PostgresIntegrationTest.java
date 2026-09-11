package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.ReferenceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Сквозной тест Postgres: сохраняем цепочку сет → подкатегория → страта →
 * карточка и читаем обратно, проверяя, что связи сохранились.
 */
@SpringBootTest
@ActiveProfiles("test")
class PostgresIntegrationTest extends PostgresTestBase {

    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired CardRepository cardRepository;
    @Autowired ReferenceResolver references;

    @Test
    @Transactional
    void savesAndReadsFullChain() {
        // 1. Сет — справочники создаются резолвером при первом обращении
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("clothing"));
        set.setGeo(references.geo("Санкт-Петербург"));
        // id и created_at проставит база (DEFAULT), поэтому их не задаём
        SetEntity savedSet = setRepository.save(set);
        assertNotNull(savedSet.getId(), "У сохранённого сета должен появиться id");

        // 2. Подкатегория: пол и слой лежат в attrs (jsonb)
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 1);

        SubcategoryEntity subcategory = new SubcategoryEntity();
        subcategory.setSet(savedSet);
        subcategory.setTitle("Блузы и рубашки");
        subcategory.setAttrs(attrs);
        SubcategoryEntity savedSubcategory = subcategoryRepository.save(subcategory);
        assertNotNull(savedSubcategory.getId());

        // 3. Страта под этой подкатегорией
        StratumEntity stratum = new StratumEntity();
        stratum.setSubcategory(savedSubcategory);
        stratum.setItem("Рубашка");
        stratum.setQuery("Рубашка женская");
        stratum.setCount(20);
        stratum.setIsSeasonal(true);
        stratum.setBaseShare(new BigDecimal("0.500"));
        StratumEntity savedStratum = stratumRepository.save(stratum);
        assertNotNull(savedStratum.getId());

        // 4. Карточка под этой стратой
        CardEntity card = new CardEntity();
        card.setStratum(savedStratum);
        card.setSku("3974473087");
        card.setName("Рубашка в полоску");
        card.setSellerId("3463754");
        card.setCollection("base");
        card.setStatus("active");
        card.setFailedAttempts(0);
        cardRepository.save(card);

        // 5. Читаем обратно и проверяем связи по всей цепочке
        List<CardEntity> cards = cardRepository.findByStratumId(savedStratum.getId());
        assertEquals(1, cards.size());

        CardEntity read = cards.get(0);
        assertEquals("3974473087", read.getSku());
        assertEquals("Рубашка", read.getStratum().getItem());                    // карточка → страта
        assertEquals("Блузы и рубашки",
                read.getStratum().getSubcategory().getTitle());                   // страта → подкатегория
        assertEquals("f",
                read.getStratum().getSubcategory().getAttrs().get("segment"));    // атрибуты подкатегории
        assertEquals("Санкт-Петербург",
                read.getStratum().getSubcategory().getSet().getGeo().getTitle()); // подкатегория → сет
    }
}