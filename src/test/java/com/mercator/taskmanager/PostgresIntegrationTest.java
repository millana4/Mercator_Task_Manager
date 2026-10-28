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
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сквозной тест Postgres: сохраняем цепочку сет → подкатегория → страта и карточку
 * с членством в страте (stratum_card), читаем обратно и проверяем связи.
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

        // 4. Карточка товара и её членство в страте
        CardEntity card = new CardEntity();
        card.setMarketplaceId(savedSet.getMarketplace().getId());
        card.setSku("3974473087");
        card.setName("Рубашка в полоску");
        card.setSellerId("3463754");
        card.setCollection("base");
        card.setStatus("active");
        card.setFailedAttempts(0);
        CardEntity savedCard = cardRepository.saveAndFlush(card);
        cardRepository.addToStratum(savedStratum.getId(), savedCard.getId(), OffsetDateTime.now());

        // 5. Читаем обратно: члены страты, товар в сете, цепочка страта → подкатегория → сет
        List<CardEntity> members = cardRepository.findStratumMembers(savedStratum.getId());
        assertEquals(1, members.size());
        assertEquals("3974473087", members.get(0).getSku());
        assertEquals(1, cardRepository.countActiveStratumMembers(savedStratum.getId()));
        assertTrue(cardRepository.isInSet(savedSet.getId(), savedCard.getId()));

        StratumEntity read = stratumRepository.findById(savedStratum.getId()).orElseThrow();
        assertEquals("Блузы и рубашки", read.getSubcategory().getTitle());
        assertEquals("f", read.getSubcategory().getAttrs().get("segment"));
        assertEquals("Санкт-Петербург", read.getSubcategory().getSet().getGeo().getTitle());
    }
}