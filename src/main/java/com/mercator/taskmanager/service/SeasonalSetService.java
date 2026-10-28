package com.mercator.taskmanager.service;

import com.mercator.taskmanager.assignment.AssignmentCalculator;
import com.mercator.taskmanager.assignment.AssignmentCalculator.Params;
import com.mercator.taskmanager.assignment.AssignmentCalculator.SubcategoryFigures;
import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CategorySettingRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Сезонная замена сета: черновик → сбор. Только для категорий с сезонной заменой
 * (category_setting.seasonal_switch, сейчас детская одежда).
 *
 * createDraft     — черновик копией активного сета: параметры, подкатегории с долями
 *                   и флагами, страты с запросами и долями; сезон — следующий.
 * updateDraft, add/update/deleteSubcategory, add/update/deleteStratum — правка
 *                   черновика в админке; после каждой правки — пересчёт.
 * recalculate     — количества страт и квоты по формулам задания.
 * startCollecting — проверка заполненности, пересчёт, статус collecting: дальше страты
 *                   берёт очередь подбора, карточки встают в обход.
 * deleteDraft     — удалить черновик (подкатегории и страты).
 *
 * В связке маркетплейс + категория + гео одновременно не больше одного черновика
 * и одного сбора: проверяется здесь (сеты создаёт только админка).
 * Ошибки: сета или строки нет — IllegalArgumentException; неверное состояние или
 * недопустимое значение — IllegalStateException.
 */
@Service
public class SeasonalSetService {

    private static final Logger log = LoggerFactory.getLogger(SeasonalSetService.class);

    private static final List<String> OPEN_STATUSES = List.of(SetStatus.DRAFT, SetStatus.COLLECTING);

    /** Параметры черновика: заданные поля меняются, null — остаются как есть. */
    public record DraftChange(String seasonType, Short seasonYear, Integer analysisCards,
                              Integer minSubcategoryCards, BigDecimal rotationFactor) {
    }

    /** Подкатегория черновика: заданные поля меняются, null — остаются (у новой — обязательны,
     *  кроме tracked). */
    public record SubcategoryChange(String title, String segment, Integer layer,
                                    BigDecimal share, Boolean tracked) {
    }

    /** Страта черновика: заданные поля меняются, null — остаются (у новой обязательны
     *  item и query). Несезонная страта доли базы не имеет. */
    public record StratumChange(String item, String query, BigDecimal queryShare,
                                Boolean isSeasonal, BigDecimal baseShare) {
    }

    private final SetRepository setRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final StratumRepository stratumRepository;
    private final CategorySettingRepository categorySettingRepository;

    public SeasonalSetService(SetRepository setRepository,
                              SubcategoryRepository subcategoryRepository,
                              StratumRepository stratumRepository,
                              CategorySettingRepository categorySettingRepository) {
        this.setRepository = setRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.stratumRepository = stratumRepository;
        this.categorySettingRepository = categorySettingRepository;
    }

    // ---------- Черновик ----------

    /** Черновик нового сезонного сета копией активного. */
    @Transactional
    public SetEntity createDraft(UUID activeSetId) {
        SetEntity active = find(activeSetId);
        requireStatus(active, SetStatus.ACTIVE);
        requireSeasonal(active);
        requireParams(active);
        List<SetEntity> open = setsInStatuses(active, OPEN_STATUSES);
        if (!open.isEmpty()) {
            throw new IllegalStateException("В связке уже есть " + describe(open.get(0))
                    + " — новый черновик не создаётся");
        }

        SetEntity draft = new SetEntity();
        draft.setMarketplace(active.getMarketplace());
        draft.setCategory(active.getCategory());
        draft.setGeo(active.getGeo());
        draft.setStatus(SetStatus.DRAFT);
        if (active.getSeasonType() != null) {
            draft.setSeasonType(SeasonType.nextType(active.getSeasonType()));
            draft.setSeasonYear(SeasonType.nextYear(active.getSeasonType(), active.getSeasonYear()));
        }
        draft.setAnalysisCards(active.getAnalysisCards());
        draft.setMinSubcategoryCards(active.getMinSubcategoryCards());
        draft.setRotationFactor(active.getRotationFactor());
        SetEntity saved = setRepository.save(draft);

        int strata = 0;
        for (SubcategoryEntity src : subcategoryRepository.findBySetId(active.getId())) {
            SubcategoryEntity sub = new SubcategoryEntity();
            sub.setSet(saved);
            sub.setTitle(src.getTitle());
            sub.setAttrs(new HashMap<>(src.getAttrs()));
            sub.setShare(src.getShare());
            sub.setTracked(src.getTracked());
            SubcategoryEntity savedSub = subcategoryRepository.save(sub);

            for (StratumEntity srcStratum : stratumRepository.findBySubcategoryId(src.getId())) {
                StratumEntity stratum = new StratumEntity();
                stratum.setSubcategory(savedSub);
                stratum.setItem(srcStratum.getItem());
                stratum.setQuery(srcStratum.getQuery());
                stratum.setCount(srcStratum.getCount());
                stratum.setIsSeasonal(srcStratum.getIsSeasonal());
                stratum.setBaseShare(srcStratum.getBaseShare());
                stratum.setQueryShare(srcStratum.getQueryShare());
                stratum.setAnalysisQuota(srcStratum.getAnalysisQuota());
                stratumRepository.save(stratum);
                strata++;
            }
        }
        recalc(saved);

        log.info("Черновик сета {} создан из активного {}: {}, страт {}",
                saved.getId(), active.getId(), describe(saved), strata);
        return saved;
    }

    /** Сезон и параметры черновика. */
    @Transactional
    public void updateDraft(UUID draftId, DraftChange change) {
        SetEntity draft = draft(draftId);
        if (change.seasonType() != null || change.seasonYear() != null) {
            String type = change.seasonType() != null ? change.seasonType() : draft.getSeasonType();
            Short year = change.seasonYear() != null ? change.seasonYear() : draft.getSeasonYear();
            if (type == null || year == null) {
                throw new IllegalStateException("Сезон задаётся типом и годом вместе");
            }
            try {
                SeasonType.title(type, year);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(e.getMessage());
            }
            if (year < 2000 || year > 2100) {
                throw new IllegalStateException("Год сезона вне диапазона 2000–2100: " + year);
            }
            draft.setSeasonType(type);
            draft.setSeasonYear(year);
        }
        if (change.analysisCards() != null) {
            draft.setAnalysisCards(positive(change.analysisCards(), "Карточек на анализ категории"));
        }
        if (change.minSubcategoryCards() != null) {
            draft.setMinSubcategoryCards(positive(change.minSubcategoryCards(), "Минимум на подкатегорию"));
        }
        if (change.rotationFactor() != null) {
            if (change.rotationFactor().compareTo(BigDecimal.ONE) < 0) {
                throw new IllegalStateException("Коэффициент ротации меньше 1: " + change.rotationFactor());
            }
            draft.setRotationFactor(change.rotationFactor());
        }
        setRepository.save(draft);
        recalc(draft);
    }

    /** Новая подкатегория черновика (страты добавляются отдельно). */
    @Transactional
    public SubcategoryEntity addSubcategory(UUID draftId, SubcategoryChange change) {
        SetEntity draft = draft(draftId);
        if (blank(change.title()) || blank(change.segment()) || change.layer() == null
                || change.share() == null) {
            throw new IllegalStateException("У новой подкатегории обязательны название, гендер, слой и доля");
        }
        SubcategoryEntity sub = new SubcategoryEntity();
        sub.setSet(draft);
        applySubcategory(sub, change);
        requireUniqueSubcategory(draft, sub);
        SubcategoryEntity saved = subcategoryRepository.save(sub);
        recalc(draft);
        return saved;
    }

    /** Правка подкатегории черновика. */
    @Transactional
    public void updateSubcategory(UUID draftId, UUID subcategoryId, SubcategoryChange change) {
        SetEntity draft = draft(draftId);
        SubcategoryEntity sub = subcategoryOf(draft, subcategoryId);
        applySubcategory(sub, change);
        requireUniqueSubcategory(draft, sub);
        subcategoryRepository.save(sub);
        recalc(draft);
    }

    /** Удалить подкатегорию черновика вместе со стратами. */
    @Transactional
    public void deleteSubcategory(UUID draftId, UUID subcategoryId) {
        SetEntity draft = draft(draftId);
        SubcategoryEntity sub = subcategoryOf(draft, subcategoryId);
        stratumRepository.deleteAll(stratumRepository.findBySubcategoryId(sub.getId()));
        subcategoryRepository.delete(sub);
        recalc(draft);
    }

    /** Новая страта в подкатегории черновика. */
    @Transactional
    public StratumEntity addStratum(UUID draftId, UUID subcategoryId, StratumChange change) {
        SetEntity draft = draft(draftId);
        SubcategoryEntity sub = subcategoryOf(draft, subcategoryId);
        if (blank(change.item()) || blank(change.query())) {
            throw new IllegalStateException("У новой страты обязательны предмет и поисковый запрос");
        }
        StratumEntity stratum = new StratumEntity();
        stratum.setSubcategory(sub);
        stratum.setCount(0);
        stratum.setIsSeasonal(false);
        applyStratum(stratum, change);
        requireUniqueItem(stratum);
        StratumEntity saved = stratumRepository.save(stratum);
        recalc(draft);
        return saved;
    }

    /** Правка страты черновика. */
    @Transactional
    public void updateStratum(UUID draftId, UUID stratumId, StratumChange change) {
        SetEntity draft = draft(draftId);
        StratumEntity stratum = stratumOf(draft, stratumId);
        applyStratum(stratum, change);
        requireUniqueItem(stratum);
        stratumRepository.save(stratum);
        recalc(draft);
    }

    /** Удалить страту черновика. */
    @Transactional
    public void deleteStratum(UUID draftId, UUID stratumId) {
        SetEntity draft = draft(draftId);
        stratumRepository.delete(stratumOf(draft, stratumId));
        recalc(draft);
    }

    /** Пересчитать количества страт и квоты черновика по формулам задания. */
    @Transactional
    public void recalculate(UUID draftId) {
        recalc(draft(draftId));
    }

    /** Запустить сбор по черновику: дальше страты берёт очередь подбора. */
    @Transactional
    public SetEntity startCollecting(UUID draftId) {
        SetEntity draft = draft(draftId);
        requireSeasonal(draft);
        requireParams(draft);
        if (draft.getSeasonType() == null) {
            throw new IllegalStateException("У черновика не задан сезон");
        }
        List<SetEntity> collecting = setsInStatuses(draft, List.of(SetStatus.COLLECTING));
        if (!collecting.isEmpty()) {
            throw new IllegalStateException("В связке уже идёт сбор: " + describe(collecting.get(0)));
        }

        List<SubcategoryEntity> subcategories = subcategoryRepository.findBySetId(draft.getId());
        if (subcategories.isEmpty()) {
            throw new IllegalStateException("В черновике нет ни одной подкатегории");
        }
        for (SubcategoryEntity sub : subcategories) {
            if (sub.getShare() == null || sub.getShare().signum() <= 0) {
                throw new IllegalStateException("У подкатегории " + label(sub) + " не задана доля");
            }
            for (StratumEntity stratum : stratumRepository.findBySubcategoryId(sub.getId())) {
                if (stratum.getQuery() == null || stratum.getQuery().isBlank()) {
                    throw new IllegalStateException("У предмета «" + stratum.getItem() + "» подкатегории "
                            + label(sub) + " нет поискового запроса");
                }
            }
        }

        int planned = recalc(draft);
        if (planned == 0) {
            throw new IllegalStateException("В черновике нет ни одной страты с количеством больше нуля");
        }

        draft.setStatus(SetStatus.COLLECTING);
        draft.setCollectingAt(OffsetDateTime.now());
        SetEntity saved = setRepository.save(draft);
        log.info("Сет {} ({}): сбор запущен, карточек к сбору {}", saved.getId(), describe(saved), planned);
        return saved;
    }

    /**
     * Удалить черновик вместе с подкатегориями и стратами. Удаление явное, сверху вниз:
     * подкатегории и страты могли быть загружены в той же транзакции (правка черновика),
     * и удаление одного сета оставило бы в контексте ссылки на удалённую запись.
     */
    @Transactional
    public void deleteDraft(UUID draftId) {
        SetEntity draft = draft(draftId);
        String description = describe(draft);
        List<SubcategoryEntity> subcategories = subcategoryRepository.findBySetId(draftId);
        for (SubcategoryEntity sub : subcategories) {
            stratumRepository.deleteAll(stratumRepository.findBySubcategoryId(sub.getId()));
        }
        subcategoryRepository.deleteAll(subcategories);
        setRepository.delete(draft);
        log.info("Черновик сета {} ({}) удалён", draftId, description);
    }

    // ---------- Расчёт ----------

    // Количества страт и квоты по формулам. Возвращает сумму количеств (план парсинга).
    private int recalc(SetEntity set) {
        Params params = new Params(set.getAnalysisCards(), set.getMinSubcategoryCards(),
                set.getRotationFactor());
        int planned = 0;
        for (SubcategoryEntity sub : subcategoryRepository.findBySetId(set.getId())) {
            if (sub.getShare() == null) {
                throw new IllegalStateException("У подкатегории " + label(sub) + " не задана доля");
            }
            SubcategoryFigures figures = AssignmentCalculator.subcategory(
                    sub.getShare(), Boolean.TRUE.equals(sub.getTracked()), params);
            List<StratumEntity> strata = stratumRepository.findBySubcategoryId(sub.getId());
            for (StratumEntity stratum : strata) {
                int count = AssignmentCalculator.stratumCount(figures.total(), stratum.getQueryShare());
                stratum.setCount(count);
                stratum.setAnalysisQuota(AssignmentCalculator.analysisQuota(
                        sub.getShare(), stratum.getQueryShare(), params));
                planned += count;
            }
            stratumRepository.saveAll(strata);
        }
        return planned;
    }

    // ---------- Правка строк ----------

    private static void applySubcategory(SubcategoryEntity sub, SubcategoryChange change) {
        if (change.title() != null) {
            if (change.title().isBlank()) {
                throw new IllegalStateException("Название подкатегории пустое");
            }
            sub.setTitle(change.title().trim());
        }
        Map<String, Object> attrs = new HashMap<>(sub.getAttrs());
        if (change.segment() != null) {
            if (change.segment().isBlank()) {
                throw new IllegalStateException("Гендер подкатегории пустой");
            }
            attrs.put("segment", change.segment().trim());
        }
        if (change.layer() != null) {
            if (change.layer() < 0 || change.layer() > 3) {
                throw new IllegalStateException("Слой вне диапазона 0–3: " + change.layer());
            }
            attrs.put("layer", change.layer());
        }
        sub.setAttrs(attrs);
        if (change.share() != null) {
            sub.setShare(share(change.share(), "Доля подкатегории"));
        }
        if (change.tracked() != null) {
            sub.setTracked(change.tracked());
        }
    }

    private static void applyStratum(StratumEntity stratum, StratumChange change) {
        if (change.item() != null) {
            if (change.item().isBlank()) {
                throw new IllegalStateException("Предмет страты пустой");
            }
            stratum.setItem(change.item().trim());
        }
        if (change.query() != null) {
            if (change.query().isBlank()) {
                throw new IllegalStateException("Поисковый запрос пустой");
            }
            stratum.setQuery(change.query().trim());
        }
        if (change.queryShare() != null) {
            stratum.setQueryShare(share(change.queryShare(), "Доля запроса"));
        }
        if (change.isSeasonal() != null) {
            stratum.setIsSeasonal(change.isSeasonal());
        }
        if (change.baseShare() != null) {
            if (change.baseShare().signum() < 0 || change.baseShare().compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalStateException("Доля базы вне диапазона 0–1: " + change.baseShare());
            }
            stratum.setBaseShare(change.baseShare());
        }
        if (Boolean.TRUE.equals(stratum.getIsSeasonal()) && stratum.getBaseShare() == null) {
            throw new IllegalStateException("У сезонной страты «" + stratum.getItem() + "» не задана доля базы");
        }
        if (!Boolean.TRUE.equals(stratum.getIsSeasonal())) {
            stratum.setBaseShare(null);
        }
    }

    private void requireUniqueSubcategory(SetEntity draft, SubcategoryEntity sub) {
        boolean duplicate = subcategoryRepository.findBySetId(draft.getId()).stream()
                .anyMatch(other -> !other.getId().equals(sub.getId())
                        && other.getTitle().equals(sub.getTitle())
                        && Objects.equals(other.getAttrs().get("segment"), sub.getAttrs().get("segment"))
                        && Objects.equals(layerOf(other), layerOf(sub)));
        if (duplicate) {
            throw new IllegalStateException("Подкатегория " + label(sub) + " уже есть в черновике");
        }
    }

    private void requireUniqueItem(StratumEntity stratum) {
        boolean duplicate = stratumRepository.findBySubcategoryId(stratum.getSubcategory().getId()).stream()
                .anyMatch(other -> !other.getId().equals(stratum.getId())
                        && other.getItem().equals(stratum.getItem()));
        if (duplicate) {
            throw new IllegalStateException("Предмет «" + stratum.getItem() + "» уже есть в подкатегории");
        }
    }

    private SubcategoryEntity subcategoryOf(SetEntity draft, UUID subcategoryId) {
        SubcategoryEntity sub = subcategoryRepository.findById(subcategoryId)
                .orElseThrow(() -> new IllegalArgumentException("Подкатегория не найдена: " + subcategoryId));
        if (!sub.getSet().getId().equals(draft.getId())) {
            throw new IllegalArgumentException("Подкатегория " + subcategoryId + " не из черновика " + draft.getId());
        }
        return sub;
    }

    private StratumEntity stratumOf(SetEntity draft, UUID stratumId) {
        StratumEntity stratum = stratumRepository.findById(stratumId)
                .orElseThrow(() -> new IllegalArgumentException("Страта не найдена: " + stratumId));
        if (!stratum.getSubcategory().getSet().getId().equals(draft.getId())) {
            throw new IllegalArgumentException("Страта " + stratumId + " не из черновика " + draft.getId());
        }
        return stratum;
    }

    // ---------- Проверки ----------

    private SetEntity draft(UUID draftId) {
        SetEntity set = find(draftId);
        requireStatus(set, SetStatus.DRAFT);
        return set;
    }

    private SetEntity find(UUID setId) {
        return setRepository.findById(setId)
                .orElseThrow(() -> new IllegalArgumentException("Сет не найден: " + setId));
    }

    private static void requireStatus(SetEntity set, String status) {
        if (!status.equals(set.getStatus())) {
            throw new IllegalStateException("Ожидался сет в статусе " + status + ", у сета "
                    + set.getId() + " статус " + set.getStatus());
        }
    }

    private void requireSeasonal(SetEntity set) {
        if (!categorySettingRepository.existsByCategoryIdAndSeasonalSwitchTrue(set.getCategory().getId())) {
            throw new IllegalStateException("Категория " + set.getCategory().getTitle()
                    + " не меняет сет по сезону");
        }
    }

    private static void requireParams(SetEntity set) {
        if (set.getAnalysisCards() == null || set.getMinSubcategoryCards() == null
                || set.getRotationFactor() == null) {
            throw new IllegalStateException("У сета " + set.getId() + " не заданы параметры задания");
        }
    }

    private static BigDecimal share(BigDecimal value, String what) {
        if (value.signum() <= 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalStateException(what + " вне диапазона (0; 1]: " + value);
        }
        return value;
    }

    private static int positive(int value, String what) {
        if (value <= 0) {
            throw new IllegalStateException(what + " должно быть больше нуля: " + value);
        }
        return value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private List<SetEntity> setsInStatuses(SetEntity set, Collection<String> statuses) {
        return setRepository.findByMarketplaceIdAndCategoryIdAndGeoIdAndStatusIn(
                set.getMarketplace().getId(), set.getCategory().getId(), set.getGeo().getId(), statuses);
    }

    private static Integer layerOf(SubcategoryEntity sub) {
        Object value = sub.getAttrs().get("layer");
        return value instanceof Number n ? n.intValue() : null;
    }

    private static String describe(SetEntity set) {
        String season = SeasonType.title(set.getSeasonType(), set.getSeasonYear());
        return set.getCategory().getTitle() + " / " + set.getGeo().getTitle()
                + (season != null ? " / " + season : "") + " [" + set.getStatus() + "]";
    }

    private static String label(SubcategoryEntity sub) {
        return "«" + sub.getTitle() + "» (" + sub.getAttrs().get("segment")
                + ", слой " + sub.getAttrs().get("layer") + ")";
    }
}