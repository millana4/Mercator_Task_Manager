package com.mercator.taskmanager;

import com.mercator.taskmanager.entity.SetEntity;
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
 * Сквозной тест приёма CSV: реальный файл → парсер → сервис → Postgres.
 * Проверяет, что сет создался с заданными marketplace/category/geo,
 * и что все страты из файла записались и привязаны к сету.
 *
 * Наследует PostgresTestBase — поднимает временный Postgres через Testcontainers.
 */
@ActiveProfiles("test")
@SpringBootTest
class SetImportServiceTest extends PostgresTestBase {

    @Autowired StratumCsvParser parser;
    @Autowired SetImportService importService;
    @Autowired SetClothingRepository stratumRepository;

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

        // 3. Сет создан с id и заданными атрибутами.
        assertNotNull(set.getId(), "У сохранённого сета должен быть id");
        assertEquals("ozon", set.getMarketplace());
        assertEquals("clothing", set.getCategory());
        assertEquals("Санкт-Петербург", set.getGeo());

        // 4. Все страты записались и привязаны к этому сету.
        List<SetClothingEntity> saved = stratumRepository.findBySetId(set.getId());
        assertEquals(rows.size(), saved.size(),
                "Число страт в базе должно совпасть с числом строк CSV");

        // 5. Проверяем, что данные страт долетели корректно — на примере первой.
        SetClothingEntity firstSaved = saved.stream()
                .filter(s -> "Трусы".equals(s.getItem()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Не нашли страту 'Трусы'"));
        assertEquals("Трусы женские", firstSaved.getQuery());
        assertEquals("f", firstSaved.getGender());
        assertEquals((short) 0, firstSaved.getLayer());
        assertFalse(firstSaved.getIsSeasonal());
        assertNull(firstSaved.getBaseShare());

        // 6. Инвариант БД сработал: у всех сохранённых страт, где есть доля базы,
        //    сезонность = true (иначе CHECK в базе не пропустил бы вставку).
        assertTrue(saved.stream()
                        .filter(s -> s.getBaseShare() != null)
                        .allMatch(SetClothingEntity::getIsSeasonal),
                "Доля базы только у сезонных");
    }
}