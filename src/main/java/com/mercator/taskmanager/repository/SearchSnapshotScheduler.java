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

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Снимки поисковой выдачи по стратам. Расписание по категориям - из конфига
 * (taskmanager.search-snapshot.schedule, время МСК): каждую минуту проверяется,
 * каким категориям положен снимок, и по каждой страте их сетов ставится задача
 * в search:tasks. run_at - плановое время прогона, одно на все страты прогона.
 */
@Component
public class SearchSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SearchSnapshotScheduler.class);
    public static final ZoneId MSK = ZoneId.of("Europe/Moscow");

    private final StratumRepository stratumRepository;
    private final SearchTaskProducer producer;
    private final boolean enabled;
    private final Map<String, Set<LocalTime>> schedule;

    public SearchSnapshotScheduler(StratumRepository stratumRepository,
                                   SearchTaskProducer producer,
                                   @Value("${taskmanager.search-snapshot.enabled:true}") boolean enabled,
                                   @Value("${taskmanager.search-snapshot.schedule:}") String schedule) {
        this.stratumRepository = stratumRepository;
        this.producer = producer;
        this.enabled = enabled;
        this.schedule = parseSchedule(schedule);
        log.info("Снимки выдачи: {}, расписание (МСК) {}", enabled ? "включены" : "выключены", this.schedule);
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

    @Scheduled(cron = "0 * * * * *", zone = "Europe/Moscow")
    public void tick() {
        if (!enabled || schedule.isEmpty()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(MSK).truncatedTo(ChronoUnit.MINUTES);
        schedule.forEach((category, times) -> {
            if (times.contains(now.toLocalTime())) {
                try {
                    run(category, now.toOffsetDateTime());
                } catch (Exception e) {
                    log.error("Снимок выдачи по категории {} не запущен: {}", category, e.getMessage(), e);
                }
            }
        });
    }

    /** Поставить задачи снимка по всем стратам сетов категории. Возвращает число задач. */
    public int run(String category, OffsetDateTime runAt) {
        String runAtText = runAt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        List<StratumEntity> strata = stratumRepository.findByCategoryTitleWithSet(category);
        int sent = 0;
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
            try {
                producer.send(task);
                sent++;
            } catch (IllegalArgumentException e) {
                log.error("Снимок выдачи по страте {} не поставлен: {}", st.getId(), e.getMessage());
            }
        }
        log.info("Снимок выдачи {}: категория {}, поставлено задач {} из {} страт",
                runAtText, category, sent, strata.size());
        return sent;
    }
}