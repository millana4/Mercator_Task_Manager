package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.set_csv.KidsStratumRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Импорт иерархического CSV: сет → подкатегории → страты.
 *
 * Строки, относящиеся к одной подкатегории, группируются по её названию
 * и признакам (пол + слой): подкатегория создаётся один раз, страты
 * привязываются к ней. Признаки кладутся в attrs (jsonb), поэтому новая
 * категория с другим набором признаков не потребует изменения схемы.
 */
@Service
public class HierarchicalSetImportService {

    private static final Logger log = LoggerFactory.getLogger(HierarchicalSetImportService.class);

    private final SetRepository setRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final StratumRepository stratumRepository;
    private final ReferenceResolver references;

    public HierarchicalSetImportService(SetRepository setRepository,
                                        SubcategoryRepository subcategoryRepository,
                                        StratumRepository stratumRepository,
                                        ReferenceResolver references) {
        this.setRepository = setRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.stratumRepository = stratumRepository;
        this.references = references;
    }

    @Transactional
    public SetEntity importSet(String marketplace, String category, String geo,
                               List<KidsStratumRow> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Нет ни одной страты для импорта");
        }

        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace(marketplace));
        set.setCategory(references.category(category));
        set.setGeo(references.geo(geo));
        SetEntity savedSet = setRepository.save(set);

        // Ключ группировки — название подкатегории вместе с признаками:
        // "Верхняя одежда" встречается у Female, Male и Baby, и это разные
        // подкатегории.
        Map<String, SubcategoryEntity> created = new LinkedHashMap<>();
        int strataCount = 0;

        for (KidsStratumRow row : rows) {
            String key = row.getSubcategoryTitle() + "|" + row.getSegment() + "|" + row.getLayer();

            SubcategoryEntity subcategory = created.get(key);
            if (subcategory == null) {
                Map<String, Object> attrs = new HashMap<>();
                attrs.put("segment", row.getSegment());
                attrs.put("layer", row.getLayer());

                SubcategoryEntity entity = new SubcategoryEntity();
                entity.setSet(savedSet);
                entity.setTitle(row.getSubcategoryTitle());
                entity.setAttrs(attrs);
                subcategory = subcategoryRepository.save(entity);
                created.put(key, subcategory);
            }

            StratumEntity stratum = new StratumEntity();
            stratum.setSubcategory(subcategory);
            stratum.setItem(row.getItem());
            stratum.setQuery(row.getQuery());
            stratum.setCount(row.getCount());
            stratum.setIsSeasonal(row.getIsSeasonal());
            stratum.setBaseShare(row.getBaseShare());
            stratumRepository.save(stratum);
            strataCount++;
        }

        int totalCards = rows.stream().mapToInt(KidsStratumRow::getCount).sum();
        log.info("Импорт сета {}: {} подкатегорий, {} страт, {} карточек к сбору",
                savedSet.getId(), created.size(), strataCount, totalCards);

        return savedSet;
    }
}