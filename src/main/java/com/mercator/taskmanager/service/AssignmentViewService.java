package com.mercator.taskmanager.service;

import com.mercator.taskmanager.analytics.AnalyticsClient;
import com.mercator.taskmanager.assignment.AssignmentCalculator;
import com.mercator.taskmanager.assignment.AssignmentCalculator.Params;
import com.mercator.taskmanager.assignment.AssignmentCalculator.SubcategoryFigures;
import com.mercator.taskmanager.assignment.AssignmentViews.Overview;
import com.mercator.taskmanager.assignment.AssignmentViews.Progress;
import com.mercator.taskmanager.assignment.AssignmentViews.Readiness;
import com.mercator.taskmanager.assignment.AssignmentViews.SetSummary;
import com.mercator.taskmanager.assignment.AssignmentViews.SetView;
import com.mercator.taskmanager.assignment.AssignmentViews.StratumView;
import com.mercator.taskmanager.assignment.AssignmentViews.SubcategoryView;
import com.mercator.taskmanager.assignment.SeasonType;
import com.mercator.taskmanager.assignment.SetStatus;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.entity.SubcategoryEntity;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.CategoryRepository;
import com.mercator.taskmanager.repository.CategorySettingRepository;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import com.mercator.taskmanager.repository.SubcategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Просмотр задания для админки: сеты связки, задание сета как в CSV (подкатегории
 * с расчётными колонками и стратами) и ход сбора сета в статусе collecting.
 *
 * Порядок строк: подкатегории — по гендеру (девочки, мальчики, малыши), слою, названию;
 * страты — по предмету (порядка строк CSV в базе нет).
 */
@Service
public class AssignmentViewService {

    private static final Map<String, Integer> SEGMENT_ORDER = Map.of("f", 0, "m", 1, "baby", 2);

    private final SetRepository setRepository;
    private final SubcategoryRepository subcategoryRepository;
    private final StratumRepository stratumRepository;
    private final CardRepository cardRepository;
    private final CategoryRepository categoryRepository;
    private final CategorySettingRepository categorySettingRepository;
    private final AnalyticsClient analyticsClient;

    public AssignmentViewService(SetRepository setRepository,
                                 SubcategoryRepository subcategoryRepository,
                                 StratumRepository stratumRepository,
                                 CardRepository cardRepository,
                                 CategoryRepository categoryRepository,
                                 CategorySettingRepository categorySettingRepository,
                                 AnalyticsClient analyticsClient) {
        this.setRepository = setRepository;
        this.subcategoryRepository = subcategoryRepository;
        this.stratumRepository = stratumRepository;
        this.cardRepository = cardRepository;
        this.categoryRepository = categoryRepository;
        this.categorySettingRepository = categorySettingRepository;
        this.analyticsClient = analyticsClient;
    }

    /** Активный сет, черновик и сбор связки; у сбора — ход сбора. */
    @Transactional(readOnly = true)
    public Overview overview(String category, String geo, String marketplace) {
        List<SetEntity> sets = setRepository.findByMarketplaceTitleAndCategoryTitleAndGeoTitle(
                marketplace, category, geo);
        SetEntity active = byStatus(sets, SetStatus.ACTIVE);
        SetEntity draft = byStatus(sets, SetStatus.DRAFT);
        SetEntity collecting = byStatus(sets, SetStatus.COLLECTING);
        boolean seasonal = categoryRepository.findByTitle(category)
                .map(c -> categorySettingRepository.existsByCategoryIdAndSeasonalSwitchTrue(c.getId()))
                .orElse(false);
        return new Overview(category, geo, marketplace, seasonal,
                summaryOrNull(active), summaryOrNull(draft), summaryOrNull(collecting),
                collecting == null ? null : progress(collecting.getId()));
    }

    /** Задание сета целиком. */
    @Transactional(readOnly = true)
    public SetView view(UUID setId) {
        SetEntity set = find(setId);
        Params params = params(set);
        Map<UUID, List<StratumEntity>> strataBySub = stratumRepository.findBySubcategorySetId(setId)
                .stream().collect(Collectors.groupingBy(st -> st.getSubcategory().getId()));

        List<SubcategoryView> subs = new ArrayList<>();
        for (SubcategoryEntity sub : sorted(subcategoryRepository.findBySetId(setId))) {
            SubcategoryFigures f = params != null && sub.getShare() != null
                    ? AssignmentCalculator.subcategory(sub.getShare(), Boolean.TRUE.equals(sub.getTracked()), params)
                    : null;
            List<StratumView> strata = strataBySub.getOrDefault(sub.getId(), List.of()).stream()
                    .sorted(Comparator.comparing(StratumEntity::getItem))
                    .map(st -> new StratumView(st.getId(), st.getItem(), st.getQuery(), st.getQueryShare(),
                            Boolean.TRUE.equals(st.getIsSeasonal()), st.getBaseShare(),
                            st.getCount(), st.getAnalysisQuota()))
                    .toList();
            subs.add(new SubcategoryView(sub.getId(), sub.getTitle(), segment(sub), layer(sub),
                    sub.getShare(), Boolean.TRUE.equals(sub.getTracked()),
                    f == null ? null : f.base(), f == null ? null : f.withAddition(),
                    f == null ? null : f.withRotation(), f == null ? null : f.total(), strata));
        }
        return new SetView(set.getCategory().getTitle(), set.getGeo().getTitle(),
                set.getMarketplace().getTitle(), summary(set), subs);
    }

    /** Ход сбора сета: страты с отправленным подбором, набранные карточки, готовность аналитики. */
    @Transactional(readOnly = true)
    public Progress progress(UUID setId) {
        SetEntity set = find(setId);
        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(setId).stream()
                .filter(st -> st.getCount() > 0)
                .toList();
        int sent = (int) strata.stream().filter(st -> st.getSelectRequestedAt() != null).count();
        int planned = strata.stream().mapToInt(StratumEntity::getCount).sum();
        int collected = (int) cardRepository.countActiveSetMembers(setId);

        Readiness readiness = null;
        String readinessError = null;
        if (SetStatus.COLLECTING.equals(set.getStatus()) || SetStatus.ACTIVE.equals(set.getStatus())) {
            try {
                AnalyticsClient.Readiness r = analyticsClient.setReadiness(setId);
                AnalyticsClient.AlgorithmState ewma = r.algorithm("EWMA");
                AnalyticsClient.AlgorithmState macd = r.algorithm("MACD");
                readiness = new Readiness(ewma == null ? null : ewma.sufficient(),
                        ewma == null ? null : ewma.nCards(),
                        macd == null ? null : macd.sufficient(),
                        macd == null ? null : macd.nCards(), r.readyForSwitch());
            } catch (RuntimeException e) {
                readinessError = e.getMessage();
            }
        }
        return new Progress(setId, strata.size(), sent, planned, collected, readiness, readinessError);
    }

    // ---------- Вспомогательное ----------

    private SetSummary summaryOrNull(SetEntity set) {
        return set == null ? null : summary(set);
    }

    private SetSummary summary(SetEntity set) {
        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(set.getId());
        int planned = strata.stream().mapToInt(StratumEntity::getCount).sum();
        int quota = strata.stream().map(StratumEntity::getAnalysisQuota).filter(Objects::nonNull)
                .mapToInt(Integer::intValue).sum();
        List<SubcategoryEntity> subs = subcategoryRepository.findBySetId(set.getId());
        // отслеживаемые - подкатегории со своей аналитикой (блок «Подкатегория» на дашборде)
        int tracked = (int) subs.stream().filter(s -> Boolean.TRUE.equals(s.getTracked())).count();
        return new SetSummary(set.getId(), set.getStatus(), set.getSeasonType(), set.getSeasonYear(),
                SeasonType.title(set.getSeasonType(), set.getSeasonYear()),
                set.getAnalysisCards(), set.getMinSubcategoryCards(), set.getRotationFactor(),
                subs.size(), tracked, strata.size(), planned, quota,
                set.getCreatedAt(), set.getCollectingAt(), set.getActivatedAt());
    }

    private static Params params(SetEntity set) {
        if (set.getAnalysisCards() == null || set.getMinSubcategoryCards() == null
                || set.getRotationFactor() == null) {
            return null;
        }
        return new Params(set.getAnalysisCards(), set.getMinSubcategoryCards(), set.getRotationFactor());
    }

    private static List<SubcategoryEntity> sorted(List<SubcategoryEntity> subs) {
        return subs.stream()
                .sorted(Comparator.comparing((SubcategoryEntity s) -> SEGMENT_ORDER.getOrDefault(segment(s), 9))
                        .thenComparing(s -> layer(s) == null ? 9 : layer(s))
                        .thenComparing(SubcategoryEntity::getTitle))
                .toList();
    }

    private static String segment(SubcategoryEntity sub) {
        Object value = sub.getAttrs().get("segment");
        return value == null ? null : value.toString();
    }

    private static Integer layer(SubcategoryEntity sub) {
        Object value = sub.getAttrs().get("layer");
        return value instanceof Number n ? n.intValue() : null;
    }

    private static SetEntity byStatus(List<SetEntity> sets, String status) {
        return sets.stream().filter(s -> status.equals(s.getStatus())).findFirst().orElse(null);
    }

    private SetEntity find(UUID setId) {
        return setRepository.findById(setId)
                .orElseThrow(() -> new IllegalArgumentException("Сет не найден: " + setId));
    }
}