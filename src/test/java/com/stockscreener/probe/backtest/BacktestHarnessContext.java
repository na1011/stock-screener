package com.stockscreener.probe.backtest;

import com.stockscreener.pipeline.collector.CorporateActionLedger;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;

/**
 * 시점 재현 하네스가 뜨는 컨텍스트(P17, ADR-0071).
 *
 * <p><b>전체 애플리케이션을 띄우지 않는 이유가 데이터 안전이다.</b> {@code @SpringBootTest}가
 * {@code StockScreenerApplication}을 띄우면 {@link ApplicationRunner} 셋이 함께 도는데,
 * 그중 {@code IndicatorCatalogInitializer}와 {@code PresetCatalogInitializer}는
 * <b>기동하는 것만으로 DB에 쓴다.</b> 읽기 전용 접속에서는 그 쓰기가 SQLSTATE 25006으로
 * 막히므로 <b>컨텍스트가 아예 뜨지 않는다</b> — 실제로 그렇게 죽었다.
 *
 * <p>그래서 러너를 <b>타입으로</b> 뺀다. 이름으로 둘을 지목하지 않는 이유는 <b>다음에 더해질
 * 러너가 조용히 새기 때문</b>이다 — 규칙은 "이 둘을 뺀다"가 아니라 <b>"하네스는 기동 쓰기를
 * 하지 않는다"</b>이고, 그 규칙을 코드로 적으면 필터가 타입이 된다.
 *
 * <p>MUST NOT: 러너를 모의 객체로 바꿔치기해 대신하지 않는다. 그러면 러너는 여전히 컨텍스트에
 * 있고, 어느 것을 무력화했는지가 <b>모의를 선언한 목록의 길이</b>로만 표현된다.
 *
 * <p>스캔 범위는 하네스가 실제로 부르는 것에서 왔다 — {@code IndicatorRunner}(계산),
 * {@code ConditionEvaluator}·{@code PresetCatalog}(판정), {@code CorporateActionLedger}
 * (기업 행위). 웹·보안 계층은 백테스트가 부르지 않으므로 들이지 않는다.
 *
 * <p><b>{@code CorporateActionLedger}만 스캔이 아니라 {@code @Import}로 집어 온다.</b> 그 패키지
 * ({@code pipeline.collector})에는 외부 어댑터({@code DataSourceRegistry})를 요구하는 수집기가
 * 함께 살고, 백테스트는 키를 주입하지 않으므로 그 어댑터가 등록되지 않는다 — 통째로 스캔하면
 * <b>외부 호출을 하지 않는다는 바로 그 이유 때문에</b> 컨텍스트가 죽는다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(
        basePackages = {
                "com.stockscreener.indicator",
                "com.stockscreener.screening",
                // 하네스 자신(PointInTimeScreener·AsOfCalendar). 운영 코드가 아니라
                // 시점 재현 전용이라 src/test에 두고 여기서만 스캔한다.
                "com.stockscreener.probe.backtest",
        },
        excludeFilters = {
                // 운영이 쓰는 바로 그 필터다. @SpringBootApplication이 이것을 달고, spring-boot-test가
                // TestTypeExcludeFilter를 델리게이트로 심어 테스트 클래스와 그 내부 클래스,
                // @TestComponent를 스캔에서 뺀다. 여기에 없으면 평범한 @ComponentScan이라
                // 그 보호가 통째로 빠진다 — IndicatorExtensibilityTest의 @TestConfiguration이
                // 실제로 스캔되어 DUMMY_TEST_INDICATOR가 레지스트리에 들었고, 그것이
                // PitFingerprint의 코드 목록과 시점 재현 저장본 512개에 전부 실렸다.
                //
                // MUST NOT: @TestConfiguration 하나만 이름으로 빼지 않는다. 그러면 @TestComponent가
                // 하나 생기는 날 같은 방식으로 다시 새는데, 그 누수는 저장본을 열어보기 전까지
                // 드러나지 않는다.
                @ComponentScan.Filter(
                        type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
                @ComponentScan.Filter(
                        type = FilterType.ASSIGNABLE_TYPE, classes = ApplicationRunner.class),
                // 컨트롤러는 도메인별 `…/web`에 있다(.claude/rules/backend.md). 백테스트는 HTTP를
                // 부르지 않는데, 들이면 컨트롤러가 스캔 밖 도메인(워치리스트 등)을 끌어와
                // **범위와 무관한 이유로** 컨텍스트가 죽는다.
                @ComponentScan.Filter(
                        type = FilterType.REGEX, pattern = "com\\.stockscreener\\..*\\.web\\..*"),
        })
// MUST: 엔티티 스캔을 명시한다. 기본값이 **이 설정 클래스의 패키지**라 그냥 두면 엔티티가
// 하나도 등록되지 않고, 증상이 "테이블이 없다"가 아니라 UnknownEntityException이라
// 원인이 스캔 범위라는 것이 드러나지 않는다.
@EntityScan("com.stockscreener")
@Import(CorporateActionLedger.class)
public class BacktestHarnessContext {
}
