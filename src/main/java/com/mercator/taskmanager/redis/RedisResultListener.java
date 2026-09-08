package com.mercator.taskmanager.redis;

import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Фоновый приём результатов из Redis. На каждый маркетплейс из справочника
 * поднимается два потока — обход и подбор — каждый крутит блокирующий BRPOP
 * по своему ключу результатов и передаёт JSON соответствующему обработчику.
 *
 * Список маркетплейсов читается при старте. Новая площадка появляется в
 * справочнике при импорте сета, поэтому после первого импорта нового
 * маркетплейса ядро нужно перезапустить, чтобы слушатель начал читать
 * его очередь результатов.
 *
 * BRPOP с таймаутом: поток периодически «просыпается», чтобы корректно
 * реагировать на остановку (running=false) и не висеть вечно на выключении.
 */
@Component
public class RedisResultListener {

    private static final Logger log = LoggerFactory.getLogger(RedisResultListener.class);
    private static final Duration BRPOP_TIMEOUT = Duration.ofSeconds(2);

    private final StringRedisTemplate redis;
    private final RedisKeys keys;
    private final ParseResultConsumer parseConsumer;
    private final SelectResultConsumer selectConsumer;
    private final MarketplaceRepository marketplaceRepository;
    private final String geo;

    private volatile boolean running = true;
    private ExecutorService pool;

    public RedisResultListener(StringRedisTemplate redis,
                               RedisKeys keys,
                               ParseResultConsumer parseConsumer,
                               SelectResultConsumer selectConsumer,
                               MarketplaceRepository marketplaceRepository,
                               @Value("${taskmanager.geo:Санкт-Петербург}") String geo) {
        this.redis = redis;
        this.keys = keys;
        this.parseConsumer = parseConsumer;
        this.selectConsumer = selectConsumer;
        this.marketplaceRepository = marketplaceRepository;
        this.geo = geo;
    }

    @PostConstruct
    public void start() {
        List<MarketplaceEntity> marketplaces = marketplaceRepository.findAll();
        if (marketplaces.isEmpty()) {
            log.warn("В справочнике нет ни одного маркетплейса — "
                    + "слушатель результатов не запущен (импортируйте сет и перезапустите ядро)");
            return;
        }

        pool = Executors.newFixedThreadPool(marketplaces.size() * 2);

        for (MarketplaceEntity marketplace : marketplaces) {
            String title = marketplace.getTitle();
            String parseKey;
            String selectKey;
            try {
                parseKey = keys.parseResults(title, geo);
                selectKey = keys.selectResults(title, geo);
            } catch (IllegalArgumentException e) {
                // Маркетплейс есть в справочнике, но кода для ключа нет —
                // пропускаем его, остальные слушаем как обычно.
                log.error("Маркетплейс '{}' пропущен: {}", title, e.getMessage());
                continue;
            }

            pool.submit(() -> loop(parseKey, parseConsumer::handle, "обход/" + title));
            pool.submit(() -> loop(selectKey, selectConsumer::handle, "подбор/" + title));
            log.info("RedisResultListener: слушаю {} и {}", parseKey, selectKey);
        }
    }

    private void loop(String key, java.util.function.Consumer<String> handler, String label) {
        log.info("Поток результатов ({}) слушает {}", label, key);
        while (running) {
            try {
                String json = redis.opsForList().rightPop(key, BRPOP_TIMEOUT);
                if (json == null) {
                    continue;  // таймаут BRPOP — проверяем running и ждём дальше
                }
                handler.accept(json);
            } catch (Exception e) {
                if (!running) {
                    // Идёт остановка контекста — соединение закрывается, это ожидаемо.
                    log.debug("Поток результатов ({}) завершается на остановке: {}", label, e.getMessage());
                    break;
                }
                // Не роняем поток из-за одной ошибки — логируем и продолжаем.
                log.error("Ошибка обработки результата ({}) из {}: {}", label, key, e.getMessage(), e);
            }
        }
        log.info("Поток результатов ({}) остановлен", label);
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (pool != null) {
            pool.shutdown();
        }
        log.info("RedisResultListener остановлен");
    }
}