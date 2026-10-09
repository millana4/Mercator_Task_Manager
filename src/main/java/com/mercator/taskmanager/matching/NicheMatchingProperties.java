package com.mercator.taskmanager.matching;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Настройки подбора аналогов ниши — файл niche-matching.yml (выгружен из ноутбука
 * разбора признаков). Поля — как в файле, kebab-case переводится в camelCase.
 *
 * groups — группы вида изделия: name, значения «Тип» (types) и текстовые признаки
 * для эмбеддинга (features). «Тип» вне групп — своя группа из одного значения,
 * признаки для неё — defaultFeatures.
 */
@ConfigurationProperties("niche-matching")
public record NicheMatchingProperties(
        int topN,
        Weights weights,
        double textSimFloor,
        double textSimCeil,
        double priceZeroRatio,
        double tempZeroDiff,
        double dupPriceTolerance,
        List<String> dupIgnoreKeys,
        List<String> footGroups,
        List<String> defaultFeatures,
        List<Group> groups) {

    /** Веса составляющих итоговой схожести. */
    public record Weights(double text, double price, double size, double natural, double temperature) {
    }

    /** Группа вида изделия. */
    public record Group(String name, List<String> types, List<String> features) {
    }
}