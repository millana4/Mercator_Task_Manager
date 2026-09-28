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
 * Транзакционная часть ранжирования ниши: чтение кандидатов, сохранение
 * эмбеддингов, применение отбора. Обращение к эмбеддеру — вне транзакции,
 * в NicheRankingService.
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

    /** Итог применения отбора. */
    public record ApplyResult(int selected, int added, int removed, int skipped) {
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
     * Применить отбор: первые plan-count из rankedIds (по убыванию близости) —
     * выборка ниши, остальные ранее отобранные выбывают. Затем статус ниши:
     * done — active, иначе selecting с очищенной задачей (следующая пачка).
     *
     * @return null — ниша уже не в статусе ranking (например, удалена)
     */
    @Transactional
    public ApplyResult applySelection(UUID nicheId, List<UUID> rankedIds, boolean done) {
        NicheEntity niche = nicheRepository.findById(nicheId).orElse(null);
        if (niche == null || !NicheStatus.RANKING.equals(niche.getStatus())) {
            return null;
        }

        Map<UUID, NicheCandidateEntity> byId = new HashMap<>();
        for (NicheCandidateEntity c : candidateRepository.findByNicheId(nicheId)) {
            byId.put(c.getId(), c);
        }

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

        if (done) {
            niche.setStatus(NicheStatus.ACTIVE);
        } else {
            niche.setStatus(NicheStatus.SELECTING);
            niche.setSelectTaskId(null);  // планировщик отправит следующую пачку
        }
        niche.setError(null);
        return new ApplyResult(selected, added, removed, skipped);
    }

    /**
     * Карточка для отобранного кандидата: существующая (включить в нишу)
     * или новая карточка, созданная для ниши, с первым замером.
     *
     * @return null — взять нельзя: карточка выбыла из обхода (кандидат остаётся
     *         для автодобора) или у кандидата из выдачи нет данных (unavailable)
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
            return null;  // выбыла из обхода — вернуть её решит автодобор
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