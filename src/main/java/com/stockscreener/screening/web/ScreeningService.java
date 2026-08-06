package com.stockscreener.screening.web;

import com.stockscreener.indicator.IndicatorCatalog;
import com.stockscreener.screening.ConditionGroup;
import com.stockscreener.screening.EvaluationMode;
import com.stockscreener.screening.ScreeningResult;
import com.stockscreener.screening.query.ScreeningRepository;
import com.stockscreener.screening.store.ScreenDefinition;
import com.stockscreener.screening.store.ScreenDefinitionValidator;
import com.stockscreener.screening.store.ScreenStore;
import com.stockscreener.screening.store.ScreenType;
import com.stockscreener.stock.StockReader;
import com.stockscreener.web.ApiExceptions;
import com.stockscreener.web.AsOfResolver;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 실행 요청 하나를 처리한다 — 조건의 출처를 정하고, 검증하고, 기준일을 해석하고, 실행한다.
 *
 * <p>{@code screening} 본 패키지가 아니라 {@code .web}에 있는 이유는 {@link AsOfResolver}에
 * 의존하기 때문이다. 그쪽은 "어떤 상태 코드로 거절하는가"라는 HTTP 정책을 들고 있고,
 * 도메인 패키지가 그것을 알면 배치·워치리스트가 웹 예외에 결합된다. 이 클래스는 요청 형태의
 * 입력을 다루는 <b>유스케이스 조립자</b>이지 도메인 서비스가 아니다.
 */
@Service
public class ScreeningService {

    /**
     * 한 응답에 담는 행의 상한.
     *
     * <p>클라이언트 정렬·페이징 전제(ADR-0016)에서 전량을 보내지만, 유니버스가 예상 밖으로
     * 커졌을 때 응답이 무한히 늘어나지는 않게 한다. 여기 걸리면 {@code truncated}가 참이 되고,
     * <b>그 값이 관측되는 날이 ADR-0003의 "성능이 문제가 되면 재검토한다"가 발동하는 시점</b>이다.
     */
    static final int MAX_ROWS = 2000;

    private final ScreenStore screenStore;
    private final ScreeningRepository screeningRepository;
    private final IndicatorCatalog indicatorCatalog;
    private final StockReader stockReader;
    private final AsOfResolver asOfResolver;
    private final ScreeningResponseAssembler assembler;

    public ScreeningService(ScreenStore screenStore,
                            ScreeningRepository screeningRepository,
                            IndicatorCatalog indicatorCatalog,
                            StockReader stockReader,
                            AsOfResolver asOfResolver,
                            ScreeningResponseAssembler assembler) {
        this.screenStore = screenStore;
        this.screeningRepository = screeningRepository;
        this.indicatorCatalog = indicatorCatalog;
        this.stockReader = stockReader;
        this.asOfResolver = asOfResolver;
        this.assembler = assembler;
    }

    @Transactional(readOnly = true)
    public ScreeningResponse run(ScreeningRequest request, long userId) {
        Source source = resolveSource(request, userId);

        try {
            // 저장된 화면도 검증한다. 지표가 폐기되면 이미 저장된 화면이 실행 불가가 되는데,
            // 그것이 빈 결과가 아니라 "이 조건을 고쳐야 한다"로 보여야 한다.
            ScreenDefinitionValidator.validate(source.conditions(), indicatorCatalog.activeCodes());
        } catch (ScreenDefinitionValidator.ScreenValidationException e) {
            throw ConditionPayloadMapper.toApiException(e);
        }

        EvaluationMode mode = firstNonNull(request.evaluationMode(), source.mode());
        String universeCode = firstNonNull(request.universeCode(), source.universeCode());
        if (universeCode != null && !stockReader.isSelectableUniverse(universeCode)) {
            throw new ApiExceptions.UniverseNotFoundException(universeCode);
        }

        LocalDate asOf = asOfResolver.resolve(request.asOf());

        List<ScreeningResult> results =
                screeningRepository.screen(source.conditions(), mode, asOf, universeCode);

        boolean truncated = results.size() > MAX_ROWS;
        List<ScreeningResult> page = truncated ? results.subList(0, MAX_ROWS) : results;

        return assembler.assemble(source, mode, universeCode, asOf, request.asOf(),
                results.size(), page, truncated);
    }

    /**
     * 조건이 어디서 오는가를 정한다.
     *
     * <p>셋 중 정확히 하나여야 한다. 하나도 없으면 "전 종목을 아무 조건 없이"가 되는데 그것은
     * 스크리닝이 아니고, 둘 이상이면 무엇이 이겼는지 사용자가 알 수 없다 — 둘 다 조용한 오해다.
     */
    private Source resolveSource(ScreeningRequest request, long userId) {
        List<String> specified = request.specifiedSources();
        if (specified.size() != 1) {
            throw new ApiExceptions.AmbiguousScreeningInputException(specified);
        }

        if (request.screenId() != null) {
            ScreenDefinition definition = screenStore.findById(request.screenId())
                    .filter(d -> isVisibleTo(d, userId))
                    .orElseThrow(() -> new ApiExceptions.ScreenNotFoundException(request.screenId()));
            return Source.of(definition);
        }
        if (request.presetCode() != null && !request.presetCode().isBlank()) {
            ScreenDefinition definition = screenStore.findByCode(request.presetCode())
                    .filter(ScreenDefinition::active)
                    .orElseThrow(() -> new ApiExceptions.ScreenNotFoundException(request.presetCode()));
            return Source.of(definition);
        }

        // 즉석 조건. 저장하지 않고 실행만 한다.
        ConditionGroup conditions = ConditionPayloadMapper.toDomain(request.conditions());
        return new Source(null, null, "즉석 조건", ScreenType.CUSTOM, EvaluationMode.FILTER, null, conditions);
    }

    /**
     * 남의 커스텀 화면은 <b>없는 것으로 다룬다.</b>
     *
     * <p>403은 "있지만 네 것이 아니다"를 알려주어 다른 사용자의 화면 존재 여부를 탐색할 수 있게
     * 한다. 지인이 쓰는 서비스라도 계정별 경계는 유지한다.
     */
    private static boolean isVisibleTo(ScreenDefinition definition, long userId) {
        return definition.isPreset() || Long.valueOf(userId).equals(definition.ownerUserId());
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    /**
     * 실행에 필요한 만큼으로 정규화한 조건 출처.
     *
     * <p>저장된 화면이든 프리셋이든 즉석 조건이든 여기서부터는 구분이 없다 — 그것이 세 입력을
     * 한 엔드포인트에 둔 이유다.
     */
    record Source(Long id, String code, String name, ScreenType type,
                  EvaluationMode mode, String universeCode, ConditionGroup conditions) {

        static Source of(ScreenDefinition definition) {
            return new Source(definition.id(), definition.code(), definition.name(),
                    definition.type(), definition.mode(), definition.universeCode(),
                    definition.conditions());
        }

        Optional<String> codeIfPreset() {
            return Optional.ofNullable(code);
        }
    }
}
