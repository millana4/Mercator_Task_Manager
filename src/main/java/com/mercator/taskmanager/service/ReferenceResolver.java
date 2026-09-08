package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CategoryEntity;
import com.mercator.taskmanager.entity.GeoEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.repository.CategoryRepository;
import com.mercator.taskmanager.repository.GeoRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import org.springframework.stereotype.Service;

/**
 * Находит запись справочника по названию, а если её нет — создаёт.
 * Нужен при импорте: маркетплейс, категория и гео приходят строками
 * из формы загрузки, а в базе хранятся ссылками.
 */
@Service
public class ReferenceResolver {

    private final MarketplaceRepository marketplaceRepository;
    private final CategoryRepository categoryRepository;
    private final GeoRepository geoRepository;

    public ReferenceResolver(MarketplaceRepository marketplaceRepository,
                             CategoryRepository categoryRepository,
                             GeoRepository geoRepository) {
        this.marketplaceRepository = marketplaceRepository;
        this.categoryRepository = categoryRepository;
        this.geoRepository = geoRepository;
    }

    public MarketplaceEntity marketplace(String title) {
        return marketplaceRepository.findByTitle(title)
                .orElseGet(() -> {
                    MarketplaceEntity e = new MarketplaceEntity();
                    e.setTitle(title);
                    return marketplaceRepository.save(e);
                });
    }

    public CategoryEntity category(String title) {
        return categoryRepository.findByTitle(title)
                .orElseGet(() -> {
                    CategoryEntity e = new CategoryEntity();
                    e.setTitle(title);
                    return categoryRepository.save(e);
                });
    }

    public GeoEntity geo(String title) {
        return geoRepository.findByTitle(title)
                .orElseGet(() -> {
                    GeoEntity e = new GeoEntity();
                    e.setTitle(title);
                    return geoRepository.save(e);
                });
    }
}
