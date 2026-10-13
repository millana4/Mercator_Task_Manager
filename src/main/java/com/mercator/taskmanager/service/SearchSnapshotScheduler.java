package com.mercator.taskmanager.service;

import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.redis.SearchTaskMessage;
import com.mercator.taskmanager.redis.SearchTaskProducer;
import com.mercator.taskmanager.repository.StratumRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Снимки поисковой выдачи по стратам. Расписание по категориям — из конфига
 * (taskmanager.search-snapshot.schedule, время МСК). В момент прогона задачи
 * по всем стратам категории раскладываются на окно spread-minutes: страты
 * перемешаны, у каждой своё случайное время в своём слоте окна. Задачи ждут
 * в Redis (search:planned), тик раз в минуту перекладывает созревшие
 * в search:tasks — выдача идёт вперемешку с обходом, а не плотной серией.
 * run_at — плановое время прогона, одно на все страты прогона.
 * Ручной запуск (run) ставит все задачи сразу.
 */
@Component
public class SearchSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchSnapshotScheduler.class);
    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final StratumRepository stratumRepository;
    private final SearchTaskProducer producer;
    private final boolean enabled;
    private final Map<String, Set<LocalTime>> schedule;
    private final Duration spread;
    private final Random random = new Random();

    public SearchSnapshotScheduler(StratumRepository stratumRepository,
                                   SearchTaskProducer producer,
                                   @Value("${taskmanager.search-snapshot.enabled:true}") boolean enabled,
                                   @Value("${taskmanager.search-snapshot.schedule:}") String schedule,
                                   @Value("${taskmanager.search-snapshot.spread-minutes:240}") int spreadMinutes) {
        this.stratumRepository = stratumRepository;
        this.producer = producer;
        this.enabled = enabled;
        this.schedule = parseSchedule(schedule);
        this.spread = Duration.ofMinutes(Math.max(spreadMinutes, 0));
        log.info("Снимки выдачи: {}, расписание (МСК) {}, окно прогона {} мин",
                enabled ? "включены" : "выключены", this.schedule, this.spread.toMinutes());
    }

    /** "kids_clothing=06:37,18:37;clothing=18:37" -> {kids_clothing=[06:37, 18:37], clothing=[18:37]}. */
    public static Map<String, Set<LocalTime>> parseSchedule(String spec) {
        Map<String, Set<LocalTime>> out = new LinkedHashMap<>();
        if (spec == null || spec.isBlank()) {
            return out;
        }
        for (String part : spec.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            int eq = p.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException(
                        "Расписание снимков: ожидалось категория=ЧЧ:ММ[,ЧЧ:ММ], получено '" + p + "'");
            }
            Set<LocalTime> times = new TreeSet<>();
            for (String t : p.substring(eq + 1).split(",")) {
                if (!t.isBlank()) {
                    times.add(LocalTime.parse(t.trim()));
                }
            }
            out.put(p.substring(0, eq).trim(), times);
        }
        return out;
    }

    /**
     * Смещения от начала окна для n задач: окно делится на n равных слотов,
     * в каждом слоте — случайная точка. Миллисекунды, по возрастанию.
     */
    public static List<Long> spreadOffsets(int n, Duration window, Random random) {
        List<Long> out = new ArrayList<>(Math.max(n, 0));
        if (n <= 0) {
            return out;
        }
        long slot = window.toMillis() / n;
        for (int i = 0; i < n; i++) {
            long jitter = slot > 0 ? (long) (random.nextDouble() * slot) : 0L;
            out.add(i * slot + jitter);
        }
        return out;
    }

    @Scheduled(cron = "0 * * * * *", zone = "Europe/Moscow")
    public void tick() {
        if (!enabled) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(MSK).truncatedTo(ChronoUnit.MINUTES);
        schedule.forEach((category, times) -> {
            if (times.contains(now.toLocalTime())) {
                try {
                    plan(category, now.toOffsetDateTime());
                } catch (Exception e) {
                    log.error("Снимок выдачи по категории {} не запланирован: {}", category, e.getMessage(), e);
                }
            }
        });
        try {
            int sent = producer.sendDue(Instant.now());
            if (sent > 0) {
                log.info("Снимки выдачи: отправлено задач по плану {}", sent);
            }
        } catch (Exception e) {
            log.error("Снимки выдачи: отправка задач по плану не удалась: {}", e.getMessage(), e);
        }
    }

    /**
     * Запланировать задачи снимка по всем стратам сетов категории на окно spread.
     * При окне 0 — поставить сразу (как run). Возвращает число задач.
     */
    public int plan(String category, OffsetDateTime runAt) {
        if (spread.isZero()) {
            return run(category, runAt);
        }
        List<SearchTaskMessage> tasks = buildTasks(category, runAt);
        Collections.shuffle(tasks, random);
        List<Long> offsets = spreadOffsets(tasks.size(), spread, random);
        Instant start = runAt.toInstant();
        int planned = 0;
        for (int i = 0; i < tasks.size(); i++) {
            producer.plan(tasks.get(i), start.plusMillis(offsets.get(i)));
            planned++;
        }
        log.info("Снимок выдачи {}: категория {}, запланировано задач {} на окно {} мин",
                tasks.isEmpty() ? runAt : tasks.get(0).getRunAt(), category, planned, spread.toMinutes());
        return planned;
    }

    /** Поставить задачи снимка по всем стратам сетов категории сразу. Возвращает число задач. */
    public int run(String category, OffsetDateTime runAt) {
        List<SearchTaskMessage> tasks = buildTasks(category, runAt);
        int sent = 0;
        for (SearchTaskMessage task : tasks) {
            try {
                producer.send(task);
                sent++;
            } catch (IllegalArgumentException e) {
                log.error("Снимок выдачи по страте {} не поставлен: {}", task.getStratumId(), e.getMessage());
            }
        }
        log.info("Снимок выдачи {}: категория {}, поставлено задач {} из {} страт",
                runAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), category, sent, tasks.size());
        return sent;
    }

    /** Задачи снимка по всем стратам сетов категории; run_at — общее для прогона. */
    private List<SearchTaskMessage> buildTasks(String category, OffsetDateTime runAt) {
        String runAtText = runAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<StratumEntity> strata = stratumRepository.findByCategoryTitleWithSet(category);
        List<SearchTaskMessage> tasks = new ArrayList<>(strata.size());
        for (StratumEntity st : strata) {
            SetEntity set = st.getSubcategory().getSet();
            SearchTaskMessage task = new SearchTaskMessage();
            task.setTaskId(UUID.randomUUID());
            task.setSetId(set.getId());
            task.setStratumId(st.getId());
            task.setGeo(set.getGeo().getTitle());
            task.setMarketplace(set.getMarketplace().getTitle());
            task.setCategory(category);
            task.setQuery(st.getQuery());
            task.setRunAt(runAtText);
            tasks.add(task);
        }
        return tasks;
    }
}