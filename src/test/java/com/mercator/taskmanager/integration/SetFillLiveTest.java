package com.mercator.taskmanager.integration;

import com.mercator.taskmanager.client.OzonParserClient;
import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.contract.Ozon.SelectionResponse;
import com.mercator.taskmanager.contract.Ozon.StratumRequest;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ЖИВОЙ тест: реальный вызов парсера на localhost:8010 по одной страте.
 * Требует запущенных Postgres, ClickHouse и ПАРСЕРА, плюс существующего
 * сета со стратами в базе (залей CSV через curl, если базы нет).
 *
 * Медленный (Selenium). Помечен @Disabled, чтобы не запускался в общей
 * сборке — запускай вручную, кликнув по тесту.
 */
@SpringBootTest
@Disabled("Живой тест парсера — запускать вручную, требует запущенного парсера")
class SetFillLiveTest {

    @Autowired SetRepository setRepository;
    @Autowired StratumRepository stratumRepository;
    @Autowired OzonParserClient parserClient;

    @Test
    void selectsOneStratumFromLiveParser() {
        // 1. Берём любой существующий сет.
        var sets = setRepository.findAll();
        assertFalse(sets.isEmpty(),
                "Нет ни одного сета. Залей CSV через curl перед этим тестом.");
        var set = sets.get(0);

        // 2. Берём первую страту сета (через подкатегории).
        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(set.getId());
        assertFalse(strata.isEmpty(), "У сета нет страт");
        StratumEntity stratum = strata.get(0);

        System.out.println(">>> Пробуем страту: " + stratum.getItem()
                + " (запрос: '" + stratum.getQuery() + "', нужно " + stratum.getCount() + ")");

        // 3. Формируем запрос и зовём парсер напрямую (без записи в БД — только проверка связи).
        StratumRequest request = new StratumRequest();
        request.setQuery(stratum.getQuery());
        request.setCount(stratum.getCount());
        request.setIsSeasonal(stratum.getIsSeasonal());
        request.setBaseShare(stratum.getBaseShare() != null
                ? stratum.getBaseShare().doubleValue() : null);
        request.setExclude(List.of());

        SelectionResponse response = parserClient.select(request);

        // 4. Проверяем, что парсер ответил.
        assertNotNull(response, "Парсер вернул null");
        System.out.println(">>> Ответ парсера: ok=" + response.getOk()
                + ", запрошено=" + response.getRequestedCount()
                + ", найдено=" + response.getFoundCount());

        assertNotNull(response.getCards(), "Список карточек null");

        // 5. Показываем, что подобралось (для наглядности).
        for (OzonCard card : response.getCards()) {
            System.out.println("    - " + card.getSku() + " | " + card.getName()
                    + " | цена=" + (card.getPrice() != null ? card.getPrice().getPrice() : "?"));
        }

        assertFalse(response.getCards().isEmpty(),
                "Парсер не подобрал ни одной карточки");
    }
}