package com.mercator.taskmanager.matching;

import com.mercator.taskmanager.matching.NicheMatchingProperties.Group;
import com.mercator.taskmanager.matching.NicheMatchingProperties.Weights;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Правила подбора аналогов поверх NicheMatchingProperties: группа вида изделия
 * по «Типу», текстовые признаки группы, группы стопы, признаки, не сравниваемые
 * при поиске дублей. Конфиг проверяется при создании — с ошибкой ядро не стартует.
 */
@Component
public class NicheMatchingConfig {

    private final NicheMatchingProperties props;
    private final Map<String, String> typeToGroup = new HashMap<>();
    private final Map<String, List<String>> groupFeatures = new HashMap<>();
    private final Set<String> footGroups;
    private final Set<String> dupIgnoreKeys;

    public NicheMatchingConfig(NicheMatchingProperties props) {
        validate(props);
        this.props = props;
        for (Group group : props.groups()) {
            for (String type : group.types()) {
                String previous = typeToGroup.put(type, group.name());
                if (previous != null) {
                    throw new IllegalStateException("niche-matching: «Тип» " + type
                            + " входит в две группы: " + previous + " и " + group.name());
                }
            }
            if (group.features() != null && !group.features().isEmpty()) {
                groupFeatures.put(group.name(), List.copyOf(group.features()));
            }
        }
        this.footGroups = Set.copyOf(nullToEmpty(props.footGroups()));
        this.dupIgnoreKeys = Set.copyOf(nullToEmpty(props.dupIgnoreKeys()));
    }

    /** Группа вида изделия по «Типу»; «Тип» вне групп — своя группа. */
    public String groupOf(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        return typeToGroup.getOrDefault(type, type);
    }

    /** Текстовые признаки группы в порядке текста; нет своих — признаки по умолчанию. */
    public List<String> featuresOf(String group) {
        List<String> features = group == null ? null : groupFeatures.get(group);
        return features != null ? features : props.defaultFeatures();
    }

    /** Размер группы — длина стопы (носки, пинетки), а не рост. */
    public boolean isFootGroup(String group) {
        return group != null && footGroups.contains(group);
    }

    /** Признак не сравнивается при поиске дублей по тексту (цвет). */
    public boolean ignoredForDuplicates(String key) {
        return key != null && dupIgnoreKeys.contains(key);
    }

    public int topN() {
        return props.topN();
    }

    public Weights weights() {
        return props.weights();
    }

    public double textSimFloor() {
        return props.textSimFloor();
    }

    public double textSimCeil() {
        return props.textSimCeil();
    }

    public double priceZeroRatio() {
        return props.priceZeroRatio();
    }

    public double tempZeroDiff() {
        return props.tempZeroDiff();
    }

    public double dupPriceTolerance() {
        return props.dupPriceTolerance();
    }

    public int groupCount() {
        return props.groups().size();
    }

    private static void validate(NicheMatchingProperties p) {
        require(p.groups() != null && !p.groups().isEmpty(), "нет групп вида изделия");
        for (Group g : p.groups()) {
            require(g.name() != null && !g.name().isBlank(), "группа без имени");
            require(g.types() != null && !g.types().isEmpty(), "у группы " + g.name() + " нет значений «Тип»");
        }
        require(p.defaultFeatures() != null && !p.defaultFeatures().isEmpty(), "нет признаков по умолчанию");
        require(p.topN() > 0, "top-n должен быть больше 0");
        Weights w = p.weights();
        require(w != null, "нет весов");
        require(w.text() >= 0 && w.price() >= 0 && w.size() >= 0 && w.natural() >= 0 && w.temperature() >= 0,
                "веса не могут быть отрицательными");
        require(w.text() + w.price() + w.size() + w.natural() + w.temperature() > 0, "сумма весов равна 0");
        require(p.textSimFloor() < p.textSimCeil(), "text-sim-floor должен быть меньше text-sim-ceil");
        require(p.priceZeroRatio() > 1, "price-zero-ratio должен быть больше 1");
        require(p.tempZeroDiff() > 0, "temp-zero-diff должен быть больше 0");
        require(p.dupPriceTolerance() >= 0, "dup-price-tolerance не может быть отрицательным");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("niche-matching: " + message);
        }
    }

    private static List<String> nullToEmpty(List<String> list) {
        return list == null ? List.of() : list;
    }
}