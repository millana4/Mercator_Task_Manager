package com.mercator.taskmanager.matching;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Отсев задвоений при наборе аналогов. Кандидаты проверяются по убыванию схожести;
 * взятые передаются в take(). Кандидат отсеивается, если:
 *  - та же модель: его sku пересекаются со sku модели референса или уже взятого аналога
 *    (модель — карточка и все значения её переключателей «Размер» и «Цвет»);
 *  - дубль по тексту: все признаки группы, кроме цвета, совпадают с уже взятым,
 *    а цены за штуку различаются не больше dup-price-tolerance.
 */
public final class DuplicateFilter {

    public static final String REJECT_SAME_MODEL = "та же модель";
    public static final String REJECT_TEXT_DUPLICATE = "дубль по тексту";

    private final NicheMatchingConfig config;
    private final Set<String> seenModels;
    private final List<CardFeatures> taken = new ArrayList<>();

    public DuplicateFilter(NicheMatchingConfig config, CardFeatures reference) {
        this.config = config;
        this.seenModels = new HashSet<>(reference.modelSkus());
    }

    /** Причина отсева или null, если кандидат не задваивает референс и взятые аналоги. */
    public String rejectReason(CardFeatures cand) {
        for (String sku : cand.modelSkus()) {
            if (seenModels.contains(sku)) {
                return REJECT_SAME_MODEL;
            }
        }
        for (CardFeatures t : taken) {
            if (isTextDuplicate(cand, t)) {
                return REJECT_TEXT_DUPLICATE;
            }
        }
        return null;
    }

    /** Кандидат взят в нишу. */
    public void take(CardFeatures cand) {
        seenModels.addAll(cand.modelSkus());
        taken.add(cand);
    }

    private boolean isTextDuplicate(CardFeatures a, CardFeatures b) {
        if (!valuesWithoutIgnored(a).equals(valuesWithoutIgnored(b))) {
            return false;
        }
        Double p1 = a.unitPrice();
        Double p2 = b.unitPrice();
        return p1 != null && p2 != null && Math.abs(p1 - p2) / Math.max(p1, p2) <= config.dupPriceTolerance();
    }

    private List<String> valuesWithoutIgnored(CardFeatures f) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> e : f.featureValues().entrySet()) {
            if (!config.ignoredForDuplicates(e.getKey())) {
                values.add(e.getValue());
            }
        }
        return values;
    }
}