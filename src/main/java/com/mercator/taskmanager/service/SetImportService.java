package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import com.mercator.taskmanager.set_csv.StratumRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Импорт плоского CSV одежды (старый формат, одна строка = одна страта).
 *
 * В новой схеме между сетом и стратой есть подкатегория, поэтому каждая
 * страта заворачивается в подкатегорию-обёртку: её title = название предмета,
 * а attrs хранят признаки, которые раньше лежали в самой страте (пол, слой).
 * Так старый формат продолжает работать без изменений на стороне пользователя.
 */
@Service
public class SetImportService {

    private final SetRepository setRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final StratumRepository stratumRepository;
    private final ReferenceResolver references;

    public SetImportService(SetRepository setRepository,
                            SubcategoryRepository subcategoryRepository,
                            StratumRepository stratumRepository,
                            ReferenceResolver references) {
        this.setRepository = setRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.stratumRepository = stratumRepository;
        this.references = references;
    }

    @Transactional
    public SetEntity importSet(String marketplace, String category, String geo, List<StratumRow> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("Нет ни одной страты для импорта");
        }

        // 1. Шапка сета — ссылки на справочники (создаются при первом появлении).
        SetEntity set = new SetEntity();
        set.setMarketplace(references.marketplace(marketplace));
        set.setCategory(references.category(category));
        set.setGeo(references.geo(geo));
        SetEntity savedSet = setRepository.save(set);

        // 2. На каждую строку — подкатегория-обёртка и страта внутри неё.
        for (StratumRow row : rows) {
            Map<String, Object> attrs = new HashMap<>();
            attrs.put("segment", row.getGender());
            attrs.put("layer", row.getLayer());

            SubcategoryEntity subcategory = new SubcategoryEntity();
            subcategory.setSet(savedSet);
            subcategory.setTitle(row.getItem());
            subcategory.setAttrs(attrs);
            SubcategoryEntity savedSubcategory = subcategoryRepository.save(subcategory);

            StratumEntity stratum = new StratumEntity();
            stratum.setSubcategory(savedSubcategory);
            stratum.setItem(row.getItem());
            stratum.setQuery(row.getQuery());
            stratum.setCount(row.getCount());
            stratum.setIsSeasonal(row.getIsSeasonal());
            stratum.setBaseShare(row.getBaseShare());
            stratumRepository.save(stratum);
        }

        return savedSet;
    }
}