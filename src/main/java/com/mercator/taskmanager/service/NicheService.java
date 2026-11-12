package com.mercator.taskmanager.service;

import com.mercator.taskmanager.controller.NicheCreateRequest;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import com.mercator.taskmanager.repository.SetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Приём и удаление заданий ниш.
 * Создание идемпотентно по externalId: повторная отправка того же задания
 * возвращает уже созданную нишу.
 */
@Service
public class NicheService {

    private static final Logger log = LoggerFactory.getLogger(NicheService.class);

    private final NicheRepository nicheRepository;
    private final SetRepository setRepository;
    private final CardRepository cardRepository;
    private final ReferenceUrlParser urlParser;
    private final int targetCount;
    private final int planCount;

    public NicheService(NicheRepository nicheRepository,
                        SetRepository setRepository,
                        CardRepository cardRepository,
                        ReferenceUrlParser urlParser,
                        @Value("${taskmanager.niche.target-count:40}") int targetCount,
                        @Value("${taskmanager.niche.plan-count:50}") int planCount) {
        this.nicheRepository = nicheRepository;
        this.setRepository = setRepository;
        this.cardRepository = cardRepository;
        this.urlParser = urlParser;
        this.targetCount = targetCount;
        this.planCount = planCount;
    }

    @Transactional
    public NicheEntity create(NicheCreateRequest req) {
        if (req.externalId() == null) {
            throw new IllegalArgumentException("Не указан externalId");
        }

        // ---------- 1. Повторная отправка — вернуть уже созданную ----------
        Optional<NicheEntity> existing = nicheRepository.findByExternalId(req.externalId());
        if (existing.isPresent()) {
            log.info("Ниша external_id={} уже принята (id={}), повторная отправка",
                    req.externalId(), existing.get().getId());
            return existing.get();
        }

        // ---------- 2. Проверка полей ----------
        if (isBlank(req.marketplace()) || isBlank(req.category()) || isBlank(req.geo())) {
            throw new IllegalArgumentException("Не указаны marketplace, category или geo");
        }
        String description = trimOrNull(req.description());
        String referenceUrl = trimOrNull(req.referenceUrl());
        // аналоги подбираются только по карточке-референсу; описание — по желанию
        if (referenceUrl == null) {
            throw new IllegalArgumentException("Нужна ссылка на карточку-референс");
        }

        // ---------- 3. Связка должна соответствовать существующему сету ----------
        List<SetEntity> sets = setRepository.findByMarketplaceTitleAndCategoryTitleAndGeoTitle(
                req.marketplace(), req.category(), req.geo());
        if (sets.isEmpty()) {
            throw new IllegalArgumentException("Нет сета для связки "
                    + req.marketplace() + " / " + req.category() + " / " + req.geo());
        }
        // справочники у всех сетов одной связки одинаковые — берём первый
        SetEntity set = sets.get(0);

        // ---------- 4. sku референса ----------
        String referenceSku = urlParser.extractSku(set.getMarketplace().getTitle(), referenceUrl);

        // ---------- 5. Сохранение ----------
        NicheEntity niche = new NicheEntity();
        niche.setMarketplace(set.getMarketplace());
        niche.setCategory(set.getCategory());
        niche.setGeo(set.getGeo());
        niche.setExternalId(req.externalId());
        niche.setDescription(description);
        niche.setReferenceUrl(referenceUrl);
        niche.setReferenceSku(referenceSku);
        niche.setTargetCount(targetCount);
        niche.setPlanCount(planCount);
        niche.setStatus(NicheStatus.NEW);
        NicheEntity saved = nicheRepository.save(niche);

        log.info("Ниша принята: id={}, external_id={}, {}/{}/{}, sku референса={}",
                saved.getId(), saved.getExternalId(), req.marketplace(), req.category(),
                req.geo(), referenceSku);
        return saved;
    }

    /**
     * Мягкое удаление: статус deleted; состав ниши (niche_card) очищается;
     * карточки, созданные для неё и входящие в другие ниши, передаются одной
     * из этих ниш; остальные созданные для неё карточки выбывают (dropped).
     * Карточки страт остаются в стратах.
     *
     * @return false — ниша не найдена
     */
    @Transactional
    public boolean delete(UUID externalId) {
        NicheEntity niche = nicheRepository.findByExternalId(externalId).orElse(null);
        if (niche == null) {
            return false;
        }
        if (NicheStatus.DELETED.equals(niche.getStatus())) {
            return true;
        }

        OffsetDateTime now = OffsetDateTime.now();
        int removed = cardRepository.removeAllFromNiche(niche.getId());
        int transferred = cardRepository.transferOwnedCards(niche.getId());
        int dropped = cardRepository.dropOwnedCards(niche.getId(), now);

        niche.setStatus(NicheStatus.DELETED);
        niche.setDeletedAt(now);

        log.info("Ниша удалена: id={}, external_id={}; убрано из состава: {}, "
                        + "передано другим нишам: {}, выбыло: {}",
                niche.getId(), externalId, removed, transferred, dropped);
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}