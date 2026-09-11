package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.service.SetImportService;
import com.mercator.taskmanager.set_csv.StratumCsvParser;
import com.mercator.taskmanager.set_csv.StratumRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Сквозной тест приёма плоского CSV: реальный файл → парсер → сервис → Postgres.
 * Проверяет, что сет создался со ссылками на справочники, каждая страта
 * завёрнута в подкатегорию-обёртку, а пол и слой уехали в attrs подкатегории.
 *
 * Наследует PostgresTestBase — поднимает временный Postgres через Testcontainers.
 */
@ActiveProfiles("test")
@SpringBootTest
class SetImportServiceTest extends PostgresTestBase {

    @Autowired StratumCsvParser parser;
    @Autowired SetImportService importService;
    @Autowired StratumRepository stratumRepository;

    @Test
    @Transactional
    void importsRealCsvIntoDatabase() {
        // 1. Разбираем реальный файл.
        InputStream csv = getClass().getResourceAsStream("/csv/set_sample.csv");
        assertNotNull(csv, "Файл /csv/set_sample.csv не найден");
        List<StratumRow> rows = parser.parse(csv);
        assertFalse(rows.isEmpty(), "Парсер должен вернуть страты");

        // 2. Импортируем как новый сет.
        SetEntity set = importService.importSet(
                "ozon", "clothing", "Санкт-Петербург", rows);

        // 3. Сет создан с id, справочники подставлены по названиям.
        assertNotNull(set.getId(), "У сохранённого сета должен быть id");
        assertEquals("ozon", set.getMarketplace().getTitle());
        assertEquals("clothing", set.getCategory().getTitle());
        assertEquals("Санкт-Петербург", set.getGeo().getTitle());

        // 4. Все страты записались и привязаны к этому сету через подкатегории.
        List<StratumEntity> saved = stratumRepository.findBySubcategorySetId(set.getId());
        assertEquals(rows.size(), saved.size(),
                "Число страт в базе должно совпасть с числом строк CSV");

        // 5. Проверяем, что данные страты долетели корректно — на примере первой.
        StratumEntity firstSaved = saved.stream()
                .filter(s -> "Трусы".equals(s.getItem()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Не нашли страту 'Трусы'"));
        assertEquals("Трусы женские", firstSaved.getQuery());
        assertFalse(firstSaved.getIsSeasonal());
        assertNull(firstSaved.getBaseShare());

        // 6. Пол и слой теперь в attrs подкатегории-обёртки.
        SubcategoryEntity subcategory = firstSaved.getSubcategory();
        assertEquals("Трусы", subcategory.getTitle(),
                "Подкатегория-обёртка называется как предмет");
        assertEquals("f", subcategory.getAttrs().get("segment"));
        assertEquals(0, ((Number) subcategory.getAttrs().get("layer")).intValue());

        // 7. Инвариант БД сработал: у всех сохранённых страт, где есть доля базы,
        //    сезонность = true (иначе CHECK в базе не пропустил бы вставку).
        assertTrue(saved.stream()
                        .filter(s -> s.getBaseShare() != null)
                        .allMatch(StratumEntity::getIsSeasonal),
                "Доля базы только у сезонных");
    }
}