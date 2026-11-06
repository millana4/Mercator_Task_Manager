package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.clickhouse.CardSnapshotRepository;
import com.mercator.taskmanager.clickhouse.MeasurementRepository;
import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.service.CardLifecycleService;
import com.mercator.taskmanager.service.CardWriteService;
import com.mercator.taskmanager.service.ReferenceResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Карточка — товар (один sku — одна карточка), состав страт — stratum_card:
 * новый товар — карточка и членство; известный товар в другом сете — та же карточка
 * и второе членство; товар уже в сете — не записывается; выбытие закрывает членство,
 * а повторный подбор в новый сет возвращает карточку только в новую страту.
 */
@ActiveProfiles("test")
@SpringBootTest
@Transactional
class CardMembershipTest extends PostgresTestBase {

    private static final String GEO = "Членство-тест";

    @MockitoBean MeasurementRepository measurementRepository;
    @MockitoBean CardSnapshotRepository snapshotRepository;

    @Autowired CardWriteService writer;
    @Autowired CardLifecycleService lifecycle;
    @Autowired CardRepository cards;
    @Autowired ReferenceResolver references;
    @Autowired SetRepository setRepository;
    @Autowired SubcategoryRepository subcategoryRepository;
    @Autowired StratumRepository stratumRepository;

    private final String prefix = "MB" + System.nanoTime();

    @Test
    void newSkuCreatesCardAndMembership() {
        StratumEntity st = stratum(set(SetStatus.ACTIVE), "Худи");

        assertTrue(writer.saveCard(st, GEO, ozon(prefix + "1"), "1"));

        List<CardEntity> members = cards.findStratumMembers(st.getId());
        assertEquals(List.of(prefix + "1"), members.stream().map(CardEntity::getSku).toList());
        assertEquals(references.marketplace("ozon").getId(), members.get(0).getMarketplaceId());
        assertEquals(1, cards.countActiveStratumMembers(st.getId()));
    }

    @Test
    void knownSkuJoinsAnotherSetWithSameCard() {
        StratumEntity summer = stratum(set(SetStatus.ACTIVE), "Худи");
        StratumEntity winter = stratum(set(SetStatus.COLLECTING), "Худи");
        String sku = prefix + "2";

        writer.saveCard(summer, GEO, ozon(sku), "1");
        assertTrue(writer.saveCard(winter, GEO, ozon(sku), "1"));

        CardEntity card = cards.findBySkuAndMarketplace(sku, references.marketplace("ozon").getId())
                .orElseThrow();
        assertEquals(card.getId(), cards.findStratumMembers(summer.getId()).get(0).getId());
        assertEquals(card.getId(), cards.findStratumMembers(winter.getId()).get(0).getId());
        assertEquals(2, cards.countLiveStrata(card.getId()));
    }

    @Test
    void sameSetTwiceIsRefused() {
        SetEntity set = set(SetStatus.ACTIVE);
        StratumEntity hoodie = stratum(set, "Худи");
        StratumEntity sweatshirt = stratum(set, "Свитшот");
        String sku = prefix + "3";

        writer.saveCard(hoodie, GEO, ozon(sku), "1");
        assertFalse(writer.saveCard(sweatshirt, GEO, ozon(sku), "1"));
        assertTrue(cards.findStratumMembers(sweatshirt.getId()).isEmpty());
    }

    @Test
    void droppedCardLeavesStratumAndReturnsOnlyToNewSet() {
        StratumEntity summer = stratum(set(SetStatus.ACTIVE), "Худи");
        StratumEntity winter = stratum(set(SetStatus.COLLECTING), "Худи");
        String sku = prefix + "4";
        writer.saveCard(summer, GEO, ozon(sku), "1");
        CardEntity card = cards.findBySkuAndMarketplace(sku, references.marketplace("ozon").getId())
                .orElseThrow();

        lifecycle.markNotFound(card.getId());                  // выбыла — членство закрыто
        cards.flush();
        assertEquals("dropped", cards.findById(card.getId()).orElseThrow().getStatus());
        assertEquals(0, cards.countActiveStratumMembers(summer.getId()));
        assertEquals(1, cards.findStratumMembers(summer.getId()).size());   // в exclude остаётся

        assertTrue(writer.saveCard(winter, GEO, ozon(sku), "1"));          // снова в продаже
        cards.flush();
        assertEquals("active", cards.findById(card.getId()).orElseThrow().getStatus());
        assertEquals(1, cards.countActiveStratumMembers(winter.getId()));
        assertEquals(0, cards.countActiveStratumMembers(summer.getId()));  // в старую страту — нет
    }

    // ---------- Вспомогательное ----------

    private SetEntity set(String status) {
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace("ozon"));
        set.setCategory(references.category("kids_clothing"));
        set.setGeo(references.geo(GEO));
        set.setStatus(status);
        return setRepository.save(set);
    }

    private StratumEntity stratum(SetEntity set, String item) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("segment", "f");
        attrs.put("layer", 2);
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(set);
        sub.setTitle("Толстовки " + item);
        sub.setAttrs(attrs);
        sub = subcategoryRepository.save(sub);

        StratumEntity st = new StratumEntity();
        st.setSubcategory(sub);
        st.setItem(item);
        st.setQuery(item + " для девочки");
        st.setCount(5);
        st.setIsSeasonal(false);
        return stratumRepository.save(st);
    }

    private static OzonCard ozon(String sku) {
        OzonCard card = new OzonCard();
        card.setSku(sku);
        card.setName("Худи " + sku);
        return card;
    }
}