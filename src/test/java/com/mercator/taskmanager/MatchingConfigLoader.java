package com.mercator.taskmanager;

import com.mercator.taskmanager.matching.NicheMatchingConfig;
import com.mercator.taskmanager.matching.NicheMatchingProperties;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

/** Конфиг подбора аналогов из niche-matching.yml — без запуска Spring. */
final class MatchingConfigLoader {

    private MatchingConfigLoader() {
    }

    static NicheMatchingProperties loadProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("niche-matching.yml"));
        Properties properties = yaml.getObject();
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("niche-matching", NicheMatchingProperties.class)
                .get();
    }

    static NicheMatchingConfig load() {
        return new NicheMatchingConfig(loadProperties());
    }
}