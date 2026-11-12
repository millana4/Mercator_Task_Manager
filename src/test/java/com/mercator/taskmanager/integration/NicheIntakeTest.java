package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.controller.NicheCreateRequest;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Приём задания ниши и удаление. */
class NicheIntakeTest extends NicheTestSupport {

    @Test
    void createsNicheAndIsIdempotent() {
        createHintStratum();
        UUID ext = UUID.randomUUID();
        NicheCreateRequest req = new NicheCreateRequest(ext, "ozon", CATEGORY, GEO, "худи",
                "https://www.ozon.ru/product/hudi-" + REFERENCE_SKU + "/?at=x");

        NicheEntity first = nicheService.create(req);
        createdNiches.add(first.getId());
        NicheEntity second = nicheService.create(req);

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(count("SELECT count(*) FROM niche WHERE external_id = ?", ext)).isEqualTo(1);
        assertThat(nicheStatus(first.getId())).isEqualTo("new");
        assertThat(str("SELECT reference_sku FROM niche WHERE id = ?", first.getId()))
                .isEqualTo(REFERENCE_SKU);
        assertThat(count("SELECT plan_count FROM niche WHERE id = ?", first.getId())).isEqualTo(50);
        assertThat(count("SELECT target_count FROM niche WHERE id = ?", first.getId())).isEqualTo(40);
    }

    @Test
    void rejectsUnknownSet() {
        assertThatThrownBy(() -> nicheService.create(new NicheCreateRequest(UUID.randomUUID(),
                "ozon", "no_such_category", GEO, "худи",
                "https://www.ozon.ru/product/hudi-" + REFERENCE_SKU + "/")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Нет сета");
    }

    @Test
    void rejectsMissingReferenceUrl() {
        createHintStratum();
        assertThatThrownBy(() -> nicheService.create(new NicheCreateRequest(UUID.randomUUID(),
                "ozon", CATEGORY, GEO, "худи для девочки", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ссылка на карточку-референс");
    }

    @Test
    void rejectsShortLink() {
        createHintStratum();
        assertThatThrownBy(() -> nicheService.create(new NicheCreateRequest(UUID.randomUUID(),
                "ozon", CATEGORY, GEO, null, "https://ozon.ru/t/AbCd12")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("не на карточку товара Ozon");
    }

    @Test
    void deleteTransfersSharedCardsAndDropsOwnOnly() {
        StratumEntity st = createHintStratum();
        NicheRef a = createNiche(true, 3);
        NicheRef b = createNiche(true, 3);

        // карточка, созданная для A и входящая в A и B
        UUID shared = uuid("INSERT INTO card (marketplace_id, niche_id, sku, name) "
                + "VALUES (?, ?, ?, 'shared') RETURNING id", ozonId(), a.id(), prefix + "SH");
        exec("INSERT INTO niche_card (niche_id, card_id) VALUES (?, ?), (?, ?)",
                a.id(), shared, b.id(), shared);
        // карточка только A
        UUID own = uuid("INSERT INTO card (marketplace_id, niche_id, sku, name) "
                + "VALUES (?, ?, ?, 'own') RETURNING id", ozonId(), a.id(), prefix + "OWN");
        exec("INSERT INTO niche_card (niche_id, card_id) VALUES (?, ?)", a.id(), own);
        // карточка страты в составе A
        UUID stratumCard = uuid("INSERT INTO card (marketplace_id, sku, name) VALUES (?, ?, 'st') RETURNING id",
                ozonId(), prefix + "ST");
        exec("INSERT INTO stratum_card (stratum_id, card_id) VALUES (?, ?)", st.getId(), stratumCard);
        exec("INSERT INTO niche_card (niche_id, card_id) VALUES (?, ?)", a.id(), stratumCard);
        // карточка, созданная для A и состоящая в страте живого сета, — тоже не выбывает
        UUID ownInSet = uuid("INSERT INTO card (marketplace_id, niche_id, sku, name) "
                + "VALUES (?, ?, ?, 'own-set') RETURNING id", ozonId(), a.id(), prefix + "OWNSET");
        exec("INSERT INTO stratum_card (stratum_id, card_id) VALUES (?, ?)", st.getId(), ownInSet);
        exec("INSERT INTO niche_card (niche_id, card_id) VALUES (?, ?)", a.id(), ownInSet);

        assertThat(nicheService.delete(a.externalId())).isTrue();

        assertThat(nicheStatus(a.id())).isEqualTo("deleted");
        assertThat(members(a.id())).isEmpty();
        // общая карточка передана B и остаётся в обходе
        assertThat(uuid("SELECT niche_id FROM card WHERE id = ?", shared)).isEqualTo(b.id());
        assertThat(cardStatus(prefix + "SH")).isEqualTo("active");
        assertThat(members(b.id())).containsExactly(prefix + "SH");
        // карточка только A выбыла
        assertThat(cardStatus(prefix + "OWN")).isEqualTo("dropped");
        // карточка страты осталась в страте
        assertThat(cardStatus(prefix + "ST")).isEqualTo("active");
        assertThat(uuid("SELECT stratum_id FROM stratum_card WHERE card_id = ?", stratumCard))
                .isEqualTo(st.getId());
        // карточка ниши, состоящая в страте живого сета, в обходе остаётся
        assertThat(cardStatus(prefix + "OWNSET")).isEqualTo("active");
    }

    @Test
    void deleteUnknownNicheReturnsFalse() {
        assertThat(nicheService.delete(UUID.randomUUID())).isFalse();
    }
}