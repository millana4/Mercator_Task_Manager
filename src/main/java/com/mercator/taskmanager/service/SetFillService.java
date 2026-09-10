package com.mercator.taskmanager.service;

import com.mercator.taskmanager.contract.Ozon.OzonCard;
import com.mercator.taskmanager.contract.Ozon.SelectionResponse;
import com.mercator.taskmanager.contract.Ozon.StratumRequest;
import com.mercator.taskmanager.client.OzonParserClient;
import com.mercator.taskmanager.entity.SetEntity;
import com.mercator.taskmanager.entity.StratumEntity;
import com.mercator.taskmanager.repository.SetRepository;
import com.mercator.taskmanager.repository.StratumRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Оркестрация синхронного наполнения сета: обходит страты сета,
 * по каждой запрашивает у парсера подбор карточек (прямой HTTP)
 * и передаёт результат в CardWriteService для записи в базы.
 *
 * Сам записью не занимается — только координирует обход и вызовы.
 * Устойчив к сбою отдельной страты: логирует ошибку и продолжает
 * с остальными, а не прерывает наполнение целиком.
 *
 * Это синхронный путь, оставленный для ручной отладки. Основной
 * (асинхронный) путь наполнения идёт через Redis: там задачи уходят
 * в список, а результаты принимает консьюмер, использующий тот же
 * CardWriteService. Логика записи карточки у обоих путей общая.
 */
@Service
public class SetFillService {

    private static final Logger log = LoggerFactory.getLogger(SetFillService.class);

    private final SetRepository setRepository;
    private final StratumRepository stratumRepository;
    private final OzonParserClient parserClient;
    private final CardWriteService cardWriteService;

    public SetFillService(SetRepository setRepository,
                          StratumRepository stratumRepository,
                          OzonParserClient parserClient,
                          CardWriteService cardWriteService) {
        this.setRepository = setRepository;
        this.stratumRepository = stratumRepository;
        this.parserClient = parserClient;
        this.cardWriteService = cardWriteService;
    }

    /** Наполнить сет. Возвращает, сколько карточек всего записано. */
    public int fillSet(UUID setId) {
        SetEntity set = setRepository.findById(setId)
                .orElseThrow(() -> new IllegalArgumentException("Сет не найден: " + setId));

        List<StratumEntity> strata = stratumRepository.findBySubcategorySetId(setId);
        log.info("Наполнение сета {}: {} страт", setId, strata.size());

        int totalCards = 0;
        for (StratumEntity stratum : strata) {
            try {
                totalCards += fillStratum(set, stratum);
            } catch (Exception e) {
                log.error("Страта '{}' (id={}) не наполнена: {}",
                        stratum.getItem(), stratum.getId(), e.getMessage(), e);
            }
        }

        log.info("Наполнение сета {} завершено: {} карточек", setId, totalCards);
        return totalCards;
    }

    /** Наполнить одну страту: позвать парсер и разложить карточки. */
    private int fillStratum(SetEntity set, StratumEntity stratum) {
        StratumRequest request = new StratumRequest();
        request.setQuery(stratum.getQuery());
        request.setCount(stratum.getCount());
        request.setIsSeasonal(stratum.getIsSeasonal());
        request.setBaseShare(stratum.getBaseShare() != null
                ? stratum.getBaseShare().doubleValue() : null);
        request.setExclude(List.of());

        log.info("Страта '{}': запрос подбора {} карточек", stratum.getItem(), stratum.getCount());
        SelectionResponse response = parserClient.select(request);

        if (response == null || response.getCards() == null) {
            log.warn("Страта '{}': пустой ответ парсера", stratum.getItem());
            return 0;
        }

        int saved = 0;
        for (OzonCard card : response.getCards()) {
            cardWriteService.saveCard(stratum, set.getGeo().getTitle(), card, null);
            saved++;
        }

        log.info("Страта '{}': записано {} из запрошенных {}",
                stratum.getItem(), saved, stratum.getCount());
        return saved;
    }
}