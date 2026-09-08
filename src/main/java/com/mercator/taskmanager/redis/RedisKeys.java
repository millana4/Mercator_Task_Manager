package com.mercator.taskmanager.redis;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Строит ключи Redis-списков из префиксов (application.yaml), кода
 * маркетплейса и кода гео: "<prefix>:<маркетплейс>:<гео>", например
 * "parse:tasks:ozon:spb".
 *
 * Разделение по маркетплейсам позволяет настроить каждый парсер на свой
 * набор площадок: воркер делает BRPOP только по тем ключам, которые ему
 * разрешены в .env.
 */
@Component
public class RedisKeys {

    private final String parseTasksPrefix;
    private final String parseResultsPrefix;
    private final String selectTasksPrefix;
    private final String selectResultsPrefix;

    public RedisKeys(
            @Value("${taskmanager.redis.parse-tasks-prefix}") String parseTasksPrefix,
            @Value("${taskmanager.redis.parse-results-prefix}") String parseResultsPrefix,
            @Value("${taskmanager.redis.select-tasks-prefix}") String selectTasksPrefix,
            @Value("${taskmanager.redis.select-results-prefix}") String selectResultsPrefix) {
        this.parseTasksPrefix = parseTasksPrefix;
        this.parseResultsPrefix = parseResultsPrefix;
        this.selectTasksPrefix = selectTasksPrefix;
        this.selectResultsPrefix = selectResultsPrefix;
    }

    private String key(String prefix, String marketplace, String geo) {
        return prefix + ":" + MarketplaceTopics.codeOf(marketplace) + ":" + GeoTopics.codeOf(geo);
    }

    public String parseTasks(String marketplace, String geo) {
        return key(parseTasksPrefix, marketplace, geo);
    }

    public String parseResults(String marketplace, String geo) {
        return key(parseResultsPrefix, marketplace, geo);
    }

    public String selectTasks(String marketplace, String geo) {
        return key(selectTasksPrefix, marketplace, geo);
    }

    public String selectResults(String marketplace, String geo) {
        return key(selectResultsPrefix, marketplace, geo);
    }
}
