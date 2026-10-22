package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.MarketplaceEntity;
import com.mercator.taskmanager.redis.ParseTaskMessage;
import com.mercator.taskmanager.redis.ParseTaskProducer;
import com.mercator.taskmanager.redis.RedisKeys;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.MarketplaceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Плановый обход карточек через Redis-очередь parse:tasks:<маркетплейс>:<гео>.
 *
 * Интервал по категориям (intervals, часы): карточка — кандидат, только когда
 * с её последнего замера прошёл интервал её категории. Первыми идут самые
 * просроченные (раньше всех наступил срок). Категории не из списка в обход
 * не попадают.
 *
 * Равномерность — «ведро жетонов»: каждый тик добавляет жетоны со скоростью
 * Σ(активных карточек категории / интервал) × rate-margin, потолок — размер
 * буфера. Плановых задач в тик ставится не больше, чем есть жетонов и места
 * в буфере очереди. Так задачи идут ровным потоком, а не пачкой, когда у сотни
 * карточек одновременно истёк интервал. Лишние жетоны не дают обхода чаще
 * интервала: просроченных карточек для них нет.
 *
 * Отставание: в какой-либо категории доля карточек без замера дольше
 * интервал + lag-extra-hours выше on-share. Пока отставание (снимается, когда
 * доля во всех категориях ниже off-share):
 *   - в Redis стоит ключ-сигнал резерва (со сроком жизни, обновляется каждый тик) —
 *     резервные парсеры берут задачи обхода;
 *   - плановый обход ставится без ограничения жетонами — до заполнения буфера;
 *   - учащённый обход после скачков цены на паузе.
 *
 * Учащённый обход: карточки с boost_started_at (см. PriceBoostService) ставятся
 * первыми, если с их замера прошло boost.interval-minutes.
 *
 * Потерянные задачи сами вернутся в кандидаты, когда last_enqueued_at выйдет
 * за окно ожидания (inflight-timeout). Состояние (жетоны, отставание) — в памяти;
 * перезапуск приложения начинает с пустого ведра.
 */
@Component
public class MonitoringScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonitoringScheduler.class);

    private final CardRepository cardRepository;
    private final MarketplaceRepository marketplaceRepository;
    private final ParseTaskProducer parseTaskProducer;
    private final StringRedisTemplate redis;
    private final RedisKeys keys;

    private final int queueBuffer;
    private final long inflightTimeoutMinutes;
    private final String geo;
    private final Map<String, Long> intervalHours;   // категория -> интервал обхода, ч
    private final double rateMargin;
    private final long lagExtraHours;
    private final double reserveOnShare;
    private final double reserveOffShare;
    private final long signalTtlSeconds;
    private final long boostIntervalMinutes;

    /** Состояние маркетплейса между тиками: жетоны, время прошлого тика, отставание. */
    static final class MarketplaceState {
        double tokens;
        OffsetDateTime lastTick;
        boolean lagging;
    }

    private final Map<Short, MarketplaceState> states = new HashMap<>();

    public MonitoringScheduler(CardRepository cardRepository,
                               MarketplaceRepository marketplaceRepository,
                               ParseTaskProducer parseTaskProducer,
                               StringRedisTemplate redis,
                               RedisKeys keys,
                               @Value("${taskmanager.monitoring.queue-buffer}") int queueBuffer,
                               @Value("${taskmanager.monitoring.inflight-timeout-minutes}") long inflightTimeoutMinutes,
                               @Value("${taskmanager.geo}") String geo,
                               @Value("${taskmanager.monitoring.intervals}") String intervals,
                               @Value("${taskmanager.monitoring.rate-margin}") double rateMargin,
                               @Value("${taskmanager.monitoring.reserve.lag-extra-hours}") long lagExtraHours,
                               @Value("${taskmanager.monitoring.reserve.on-share}") double reserveOnShare,
                               @Value("${taskmanager.monitoring.reserve.off-share}") double reserveOffShare,
                               @Value("${taskmanager.monitoring.reserve.signal-ttl-seconds}") long signalTtlSeconds,
                               @Value("${taskmanager.monitoring.boost.interval-minutes}") long boostIntervalMinutes) {
        this.cardRepository = cardRepository;
        this.marketplaceRepository = marketplaceRepository;
        this.parseTaskProducer = parseTaskProducer;
        this.redis = redis;
        this.keys = keys;
        this.queueBuffer = queueBuffer;
        this.inflightTimeoutMinutes = inflightTimeoutMinutes;
        this.geo = geo;
        this.intervalHours = parseIntervals(intervals);
        this.rateMargin = rateMargin;
        this.lagExtraHours = lagExtraHours;
        this.reserveOnShare = reserveOnShare;
        this.reserveOffShare = reserveOffShare;
        this.signalTtlSeconds = signalTtlSeconds;
        this.boostIntervalMinutes = boostIntervalMinutes;
        log.info("Обход: интервалы (ч) {}, запас темпа {}, отставание — доля старше интервал+{} ч выше {} "
                        + "(снимается ниже {}), учащение раз в {} мин",
                this.intervalHours, rateMargin, lagExtraHours, reserveOnShare, reserveOffShare,
                boostIntervalMinutes);
    }

    /** "kids_clothing=10,clothing=24" -> {kids_clothing=10, clothing=24}. */
    public static Map<String, Long> parseIntervals(String spec) {
        Map<String, Long> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Интервалы обхода не заданы (taskmanager.monitoring.intervals)");
        }
        for (String part : spec.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(
                        "Интервалы обхода: ожидалось категория=часы, получено '" + p + "'");
            }
            long hours = Long.parseLong(p.substring(eq + 1).trim());
            if (hours <= 0) {
                throw new IllegalArgumentException("Интервал обхода должен быть больше нуля: '" + p + "'");
            }
            out.put(p.substring(0, eq).trim(), hours);
        }
        return out;
    }

    /** Новое значение отставания с порогами включения и снятия (гистерезис). */
    public static boolean nextLagging(boolean lagging, double maxOverdueShare, double onShare, double offShare) {
        if (!lagging && maxOverdueShare > onShare) {
            return true;
        }
        if (lagging && maxOverdueShare < offShare) {
            return false;
        }
        return lagging;
    }

    /** Жетоны после тика: прибавка по темпу (в час) за прошедшие секунды, не выше потолка. */
    public static double nextTokens(double tokens, double ratePerHour, double elapsedSeconds, double cap) {
        return Math.min(cap, tokens + ratePerHour * Math.max(elapsedSeconds, 0) / 3600.0);
    }

    /**
     * Тик долива. Интервал берётся из конфига (refill-interval-ms).
     * initialDelay даёт приложению и Redis подняться перед первым доливом.
     */
    @Scheduled(fixedDelayString = "${taskmanager.monitoring.refill-interval-ms}", initialDelay = 15_000)
    @Transactional
    public void refill() {
        for (MarketplaceEntity marketplace : marketplaceRepository.findAll()) {
            try {
                refillMarketplace(marketplace);
            } catch (IllegalArgumentException e) {
                // Нет кода для ключа — площадку пропускаем, остальные доливаем.
                log.error("Долив по маркетплейсу '{}' пропущен: {}",
                        marketplace.getTitle(), e.getMessage());
            }
        }
    }

    void refillMarketplace(MarketplaceEntity marketplace) {
        String title = marketplace.getTitle();
        Short marketplaceId = marketplace.getId();
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        MarketplaceState state = states.computeIfAbsent(marketplaceId, id -> new MarketplaceState());

        // 1. Темп и отставание по категориям.
        double ratePerHour = 0;
        double maxOverdueShare = 0;
        String worstCategory = null;
        for (Map.Entry<String, Long> e : intervalHours.entrySet()) {
            long total = cardRepository.countActiveInCategory(marketplaceId, e.getKey());
            if (total == 0) {
                continue;
            }
            ratePerHour += (double) total / e.getValue() * rateMargin;
            long overdue = cardRepository.countOverdueInCategory(marketplaceId, e.getKey(),
                    now.minusHours(e.getValue() + lagExtraHours));
            double share = (double) overdue / total;
            if (share > maxOverdueShare) {
                maxOverdueShare = share;
                worstCategory = e.getKey();
            }
        }
        boolean wasLagging = state.lagging;
        state.lagging = nextLagging(wasLagging, maxOverdueShare, reserveOnShare, reserveOffShare);
        if (state.lagging != wasLagging) {
            if (state.lagging) {
                log.warn("Обход {} отстаёт: просрочено {}% карточек категории {} — резерв включён, "
                                + "темп без ограничения, учащение на паузе",
                        title, Math.round(maxOverdueShare * 1000) / 10.0, worstCategory);
            } else {
                log.info("Обход {} догнал график: просрочено не больше {}% — резерв выключен",
                        title, Math.round(maxOverdueShare * 1000) / 10.0);
            }
        }
        publishReserveSignal(title, state.lagging);

        // 2. Жетоны за время с прошлого тика.
        double elapsed = state.lastTick == null ? 0 : Duration.between(state.lastTick, now).getSeconds();
        state.tokens = nextTokens(state.tokens, ratePerHour, elapsed, queueBuffer);
        state.lastTick = now;

        // 3. Место в буфере очереди.
        String queueKey = keys.parseTasks(title, geo);
        Long len = redis.opsForList().size(queueKey);
        int inQueue = (len == null) ? 0 : len.intValue();
        int free = queueBuffer - inQueue;
        if (free <= 0) {
            return;  // очередь уже полна — ничего не доливаем
        }
        OffsetDateTime inflightThreshold = now.minusMinutes(inflightTimeoutMinutes);

        // 4. Учащённый обход — первым, если нет отставания.
        int boosted = 0;
        if (!state.lagging) {
            List<CardEntity> boost = cardRepository.findBoostCandidates(marketplaceId,
                    now.minusMinutes(boostIntervalMinutes), inflightThreshold, free);
            boosted = enqueue(boost, title, now);
            free -= boosted;
        }

        // 5. Плановый обход: при отставании — до заполнения буфера, иначе — по жетонам.
        int limit = state.lagging ? free : Math.min(free, (int) Math.floor(state.tokens));
        int planned = 0;
        if (limit > 0) {
            List<CardEntity> due = dueCandidates(marketplaceId, now, inflightThreshold, limit);
            planned = enqueue(due, title, now);
            if (!state.lagging) {
                state.tokens -= planned;
            }
        }

        if (boosted + planned > 0) {
            log.info("Обход: долив очереди {} — было {}, учащённых {}, плановых {} (жетонов {}, отставание {})",
                    queueKey, inQueue, boosted, planned, Math.round(state.tokens * 10) / 10.0,
                    state.lagging ? "да" : "нет");
        }
    }

    /** Просроченные карточки всех категорий, по сроку (замер + интервал), не больше limit. */
    private List<CardEntity> dueCandidates(Short marketplaceId, OffsetDateTime now,
                                           OffsetDateTime inflightThreshold, int limit) {
        List<CardEntity> all = new ArrayList<>();
        Map<UUID, OffsetDateTime> dueAt = new HashMap<>();
        for (Map.Entry<String, Long> e : intervalHours.entrySet()) {
            for (CardEntity card : cardRepository.findDueCandidates(marketplaceId, e.getKey(),
                    now.minusHours(e.getValue()), inflightThreshold, limit)) {
                all.add(card);
                OffsetDateTime measured = card.getLastMeasuredAt();
                dueAt.put(card.getId(), measured == null ? OffsetDateTime.MIN : measured.plusHours(e.getValue()));
            }
        }
        all.sort(Comparator.comparing(c -> dueAt.get(c.getId())));
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /** Поставить задачи обхода и пометить карточки отправленными. Возвращает число задач. */
    private int enqueue(List<CardEntity> cards, String marketplaceTitle, OffsetDateTime now) {
        if (cards.isEmpty()) {
            return 0;
        }
        List<UUID> ids = new ArrayList<>(cards.size());
        for (CardEntity card : cards) {
            ParseTaskMessage task = new ParseTaskMessage();
            task.setTaskId(UUID.randomUUID());
            task.setCardId(card.getId());
            task.setSku(card.getSku());
            task.setGeo(geo);
            task.setMarketplace(marketplaceTitle);
            parseTaskProducer.send(task);
            ids.add(card.getId());
        }
        cardRepository.markEnqueued(ids, now);
        return ids.size();
    }

    /** Сигнал резерва: при отставании — ключ со сроком жизни, иначе ключ удаляется. */
    private void publishReserveSignal(String marketplaceTitle, boolean lagging) {
        String key = keys.reserveSignal(marketplaceTitle, geo);
        if (lagging) {
            redis.opsForValue().set(key, "1", Duration.ofSeconds(signalTtlSeconds));
        } else {
            redis.delete(key);
        }
    }
}