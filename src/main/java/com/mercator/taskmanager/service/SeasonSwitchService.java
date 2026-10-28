package com.mercator.taskmanager.service;

import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.SetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Переключение категории на новый сезонный сет: прежний активный сет связки — retired,
 * сет в сборе — active. Карточки не трогаются: карточка — товар (V19), обход определяется
 * членством. Товар только прежнего сета выпадает из обхода (статус и история остаются),
 * общий товар обходится дальше под тем же card_id, товар в нише — ради ниши.
 * Добор, выдача и подсказки ниш берут активный сет; дашборд аналитики показывает
 * активный сет по статусу из ядра.
 */
@Service
public class SeasonSwitchService {

    private static final Logger log = LoggerFactory.getLogger(SeasonSwitchService.class);

    /** Итог переключения. */
    public record SwitchResult(UUID activeSetId, List<UUID> retiredSetIds) {
    }

    private final SetRepository setRepository;

    public SeasonSwitchService(SetRepository setRepository) {
        this.setRepository = setRepository;
    }

    @Transactional
    public SwitchResult switchTo(UUID collectingSetId) {
        SetEntity next = setRepository.findById(collectingSetId)
                .orElseThrow(() -> new IllegalArgumentException("Сет не найден: " + collectingSetId));
        if (!SetStatus.COLLECTING.equals(next.getStatus())) {
            throw new IllegalStateException("Переключаться можно только на сет в сборе, у сета "
                    + collectingSetId + " статус " + next.getStatus());
        }
        List<SetEntity> previous = setRepository.findByMarketplaceIdAndCategoryIdAndGeoIdAndStatusIn(
                next.getMarketplace().getId(), next.getCategory().getId(), next.getGeo().getId(),
                List.of(SetStatus.ACTIVE));

        OffsetDateTime now = OffsetDateTime.now();
        List<UUID> retired = new ArrayList<>();
        for (SetEntity old : previous) {
            old.setStatus(SetStatus.RETIRED);
            old.setRetiredAt(now);
            setRepository.save(old);
            retired.add(old.getId());
        }
        next.setStatus(SetStatus.ACTIVE);
        next.setActivatedAt(now);
        setRepository.save(next);

        log.info("Переключение {} / {}: активный сет {} ({}), выведены {}",
                next.getCategory().getTitle(), next.getGeo().getTitle(), next.getId(),
                SeasonType.title(next.getSeasonType(), next.getSeasonYear()), retired);
        return new SwitchResult(next.getId(), retired);
    }
}