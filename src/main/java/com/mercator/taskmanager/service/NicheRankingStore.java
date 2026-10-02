package com.mercator.taskmanager.service;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.entity.CardEntity;
import com.mercator.taskmanager.entity.NicheCandidateEntity;
import com.mercator.taskmanager.entity.NicheEntity;
import com.mercator.taskmanager.entity.NicheStatus;
import com.mercator.taskmanager.repository.CardRepository;
import com.mercator.taskmanager.repository.NicheCandidateRepository;
import com.mercator.taskmanager.repository.NicheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Транзакционная часть ранжирования и автодобора ниши: чтение кандидатов,
 * сохранение эмбеддингов, применение отбора. Обращение к эмбеддеру — вне
 * транзакции, в NicheRankingService.
 *
 * Состав ниши — niche_card: карточка может входить в несколько ниш.
 */
@Service
public class NicheRankingStore {

    private final NicheRepository nicheRepository;
    private final NicheCandidateRepository candidateRepository;
    private final CardRepository cardRepository;
    private final CardWriteService cardWriteService;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build();

    public NicheRankingStore(NicheRepository nicheRepository,
                             NicheCandidateRepository candidateRepository,
                             CardRepository cardRepository,
                             CardWriteService cardWriteService) {
        this.nicheRepository = nicheRepository;
        this.candidateRepository = candidateRepository;
        this.cardRepository = cardRepository;
        this.cardWriteService = cardWriteService;
    }

    /** Кандидат без ленивых связей — для работы вне транзакции. */
    public record CandidateView(UUID id, String sku, String source, UUID cardId,
                                Map<String, Object> snapshot, boolean embedded,
                                Double similarity, String status) {
    }

    /** Посчитанный эмбеддинг кандидата. */
    public record Embedded(UUID candidateId, String text, float[] vector, double similarity) {
    }

    /** Итог применения отбора (первичный подбор); done — отобрано plan-count, ниша active. */
    public record ApplyResult(int selected, int added, int removed, int skipped, boolean done) {
    }

    /** Задача проверочного парсинга исторического кандидата. */
    public record VerifyTask(UUID candidateId, UUID taskId, String sku) {
    }

    /** Итог прохода автодобора. */
    public record RefillResult(int taken, int unavailable, List<VerifyTask> toVerify) {
    }

    @Transactional(readOnly = true)
    public List<CandidateView> loadCandidates(UUID nicheId) {
        List<CandidateView> views = new ArrayList<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            views.add(new CandidateView(
                    c.getId(), c.getSku(), c.getSource(),
                    c.getCard() != null ? c.getCard().getId() : null,
                    c.getSnapshot(), c.getEmbedding() != null,
                    c.getSimilarity(), c.getStatus()));
        }
        return views;
    }

    @Transactional
    public void saveEmbeddings(List<Embedded> embedded, List<UUID> unavailable) {
        for (Embedded e : embedded) {
            candidateRepository.findById(e.candidateId()).ifPresent(c -> {
                c.setEmbedText(e.text());
                c.setEmbedding(e.vector());
                c.setSimilarity(e.similarity());
            });
        }
        for (UUID id : unavailable) {
            candidateRepository.findById(id).ifPresent(c -> c.setStatus("unavailable"));
        }
    }


    /**
     * Первичный подбор: первые plan-count из rankedIds (по убыванию близости) —
     * выборка ниши, остальные ранее отобранные выбывают. Затем статус ниши:
     * отобрано plan-count — active (подбор завершён), иначе selecting с очищенной
     * задачей (следующая пачка на недостающее число). Пропущенные кандидаты
     * (карточка выбыла из обхода, нет данных) в отобранные не входят.
     *
     * @return null — ниша уже не в статусе ranking (например, удалена)
     */
    @Transactional
    public ApplyResult applySelection(UUID nicheId, List<UUID> rankedIds) {
        NicheEntity niche = nicheRepository.findById(nicheId).orElse(null);
        if (niche == null || !NicheStatus.RANKING.equals(niche.getStatus())) {
            return null;
        }

        Map<UUID, NicheCandidateEntity> byId = candidatesById(nicheId);
        int planCount = niche.getPlanCount();
        int selected = 0, added = 0, removed = 0, skipped = 0;

        for (UUID id : rankedIds) {
            NicheCandidateEntity c = byId.get(id);
            if (c == null) {
                continue;
            }
            boolean isSelected = "selected".equals(c.getStatus());

            if (selected < planCount) {
                if (isSelected) {
                    selected++;
                    continue;
                }
                CardEntity card = attachOrCreate(niche, c);
                if (card == null) {
                    skipped++;
                    continue;
                }
                c.setCard(card);
                c.setStatus("selected");
                selected++;
                added++;
            } else if (isSelected) {
                detach(niche, c.getCard());
                c.setStatus("candidate");
                removed++;
            }
        }

        boolean done = selected >= planCount;

        // Задача пачки обработана. При active — чтобы автодобор видел, что поиск
        // не идёт; при selecting — планировщик отправит следующую пачку.
        niche.setSelectTaskId(null);
        niche.setStatus(done ? NicheStatus.ACTIVE : NicheStatus.SELECTING);
        niche.setError(null);
        return new ApplyResult(selected, added, removed, skipped, done);
    }


    /**
     * Автодобор, уборка: карточки состава, выбывшие по жизненному циклу
     * (товар закончился, удалён, стабильно не парсится), убираются из ниши,
     * их кандидаты — unavailable (строка остаётся, чтобы sku был в exclude поиска).
     *
     * @return сколько карточек убрано
     */
    @Transactional
    public int cleanupDroppedMembers(UUID nicheId) {
        List<UUID> dropped = cardRepository.findDroppedMembers(nicheId);
        for (UUID cardId : dropped) {
            cardRepository.removeFromNiche(nicheId, cardId);
            cardRepository.findById(cardId)
                    .flatMap(card -> candidateRepository.findByNicheIdAndSku(nicheId, card.getSku()))
                    .ifPresent(c -> {
                        c.setStatus("unavailable");
                        c.setVerifyTaskId(null);
                        c.setVerifyRequestedAt(null);
                    });
        }
        return dropped.size();
    }


    /**
     * Автодобор: снять проверки без результата дольше порога (задача потеряна).
     * Кандидат остаётся candidate и снова участвует в выборе.
     *
     * @return сколько проверок снято
     */
    @Transactional
    public int releaseStaleVerifications(UUID nicheId, OffsetDateTime threshold) {
        int released = 0;
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            if (c.getVerifyTaskId() != null
                    && (c.getVerifyRequestedAt() == null || c.getVerifyRequestedAt().isBefore(threshold))) {
                c.setVerifyTaskId(null);
                c.setVerifyRequestedAt(null);
                released++;
            }
        }
        return released;
    }


    /**
     * Автодобор, выбор: проход по rankedIds (по убыванию близости), пока есть места.
     *  - у кандидата активная карточка (обходится, данные свежие) — сразу в нишу;
     *  - выбывшая карточка страты — unavailable;
     *  - новый кандидат последнего поиска (создан не раньше freshSince) — сразу в нишу;
     *  - исторический — место резервируется, verify_task_id; парсинг отправит вызывающий код.
     */
    @Transactional
    public RefillResult applyRefill(UUID nicheId, List<UUID> rankedIds, int slots,
                                    OffsetDateTime freshSince) {
        NicheEntity niche = nicheRepository.findById(nicheId).orElse(null);
        if (niche == null || !NicheStatus.ACTIVE.equals(niche.getStatus())) {
            return new RefillResult(0, 0, List.of());
        }

        Map<UUID, NicheCandidateEntity> byId = candidatesById(nicheId);
        int taken = 0, unavailable = 0;
        List<VerifyTask> toVerify = new ArrayList<>();

        for (UUID id : rankedIds) {
            if (slots <= 0) {
                break;
            }
            NicheCandidateEntity c = byId.get(id);
            if (c == null || !"candidate".equals(c.getStatus()) || c.getVerifyTaskId() != null) {
                continue;
            }

            CardEntity card = c.getCard();

            // Карточка обходится — данные свежие, проверка не нужна
            if (card != null && "active".equals(card.getStatus())) {
                cardRepository.addToNiche(nicheId, card.getId());
                c.setStatus("selected");
                taken++;
                slots--;
                continue;
            }

            // Карточка страты выбыла — товара нет
            if (card != null && "stratum".equals(c.getSource())) {
                c.setStatus("unavailable");
                unavailable++;
                continue;
            }

            // Новый кандидат последнего поиска — данные только что спарсены
            boolean fresh = card == null && freshSince != null && c.getCreatedAt() != null
                    && !c.getCreatedAt().isBefore(freshSince);
            if (fresh) {
                CardEntity attached = attachOrCreate(niche, c);
                if (attached != null) {
                    c.setCard(attached);
                    c.setStatus("selected");
                    taken++;
                    slots--;
                }
                continue;
            }

            // Исторический — проверочный парсинг, место резервируется
            UUID taskId = UUID.randomUUID();
            c.setVerifyTaskId(taskId);
            c.setVerifyRequestedAt(OffsetDateTime.now());
            toVerify.add(new VerifyTask(c.getId(), taskId, c.getSku()));
            slots--;
        }
        return new RefillResult(taken, unavailable, toVerify);
    }

    private Map<UUID, NicheCandidateEntity> candidatesById(UUID nicheId) {
        Map<UUID, NicheCandidateEntity> byId = new HashMap<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            byId.put(c.getId(), c);
        }
        return byId;
    }

    /**
     * Карточка для отобранного кандидата: существующая (включить в нишу)
     * или новая карточка, созданная для ниши, с первым замером.
     *
     * @return null — взять нельзя: карточка выбыла из обхода или у кандидата
     *         из выдачи нет данных (unavailable)
     */
    private CardEntity attachOrCreate(NicheEntity niche, NicheCandidateEntity c) {
        // Кандидат из страты или ранее созданная карточка
        if (c.getCard() != null) {
            return attachExisting(niche, c.getCard());
        }

        // Кандидат из выдачи: такой sku уже есть на маркетплейсе?
        Optional<CardEntity> existing =
                cardRepository.findBySkuAndMarketplace(c.getSku(), niche.getMarketplace().getId());
        if (existing.isPresent()) {
            return attachExisting(niche, existing.get());
        }

        // Новая карточка, созданная для ниши, из данных подбора
        if (c.getSnapshot() == null) {
            c.setStatus("unavailable");
            return null;
        }
        Map<String, Object> data = new HashMap<>(c.getSnapshot());
        Object parserId = data.remove("parser_id");
        OzonCard card = mapper.convertValue(data, OzonCard.class);
        CardEntity created = cardWriteService.saveNicheCard(niche, niche.getGeo().getTitle(), card,
                parserId != null ? parserId.toString() : "");
        cardRepository.addToNiche(niche.getId(), created.getId());
        return created;
    }

    private CardEntity attachExisting(NicheEntity niche, CardEntity card) {
        if (!"active".equals(card.getStatus())) {
            return null;  // выбыла из обхода
        }
        cardRepository.addToNiche(niche.getId(), card.getId());
        return card;
    }

    /**
     * Карточка выпала из выборки ниши: убрать из состава. Карточка без страты
     * выбывает из обхода, только если больше не входит ни в одну нишу.
     */
    private void detach(NicheEntity niche, CardEntity card) {
        if (card == null) {
            return;
        }
        cardRepository.removeFromNiche(niche.getId(), card.getId());
        if (card.getStratum() == null
                && cardRepository.countNiches(card.getId()) == 0
                && !"dropped".equals(card.getStatus())) {
            card.setStatus("dropped");
            card.setDroppedAt(OffsetDateTime.now());
        }
    }
}