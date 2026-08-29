package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CompanyProfile;
import com.stockscreener.pipeline.datasource.CompanyProfileDataSource;
import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.DataSourceRegistry;
import com.stockscreener.pipeline.datasource.IndustryScheme;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 한 종목의 업종·결산월을 수집해 {@code stock}에 채운다. 벤더를 모른다.
 *
 * <p>업종 상대 지표와 Altman Z의 금융업 제외가 이 컬럼에 걸려 있다(ADR-0044).
 *
 * <p>MUST NOT: 소스가 준 코드를 잘라 넘기지 않는다. 소분류·중분류 키는 저장 계층의 생성
 * 컬럼이 만든다 — 두 곳에서 자르면 어느 쪽이 참인지 알 수 없고, 어긋나도 아무 에러가 나지
 * 않은 채 종목이 다른 업종으로 묶인다.
 *
 * <p><b>어느 컬럼에 쓸지는 {@link IndustryScheme}이 정한다</b>(ADR-0092). 체계마다 UPDATE를
 * 따로 두는 이유는 <b>컬럼 이름을 문자열로 조립하지 않기 위해서</b>이고, 잘못 고른 값은
 * {@code ck_stock_sic_is_us}·{@code ck_stock_ksic_is_not_us}가 그 종목만 실패시킨다.
 */
@Component
public class CompanyProfileCollector {

    private static final Logger log = LoggerFactory.getLogger(CompanyProfileCollector.class);

    private final DataSourceRegistry registry;
    private final JdbcTemplate jdbcTemplate;

    public CompanyProfileCollector(DataSourceRegistry registry, JdbcTemplate jdbcTemplate) {
        this.registry = registry;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 개황을 받아 저장한다.
     *
     * <p>소스가 종목을 모르거나 업종코드가 비어 오면 <b>0을 낸다.</b> 예외로 올리지 않는
     * 이유는 그것이 종목 단위 실패가 아니기 때문이다 — 신규 상장 직후에 정상적으로 생기고,
     * 실패로 기록하면 매 실행마다 같은 종목이 쌓여 실제 장애를 덮는다. 대신 <b>업종이 비어
     * 있다는 사실은 업종 조건의 판정불가로 드러난다</b>(ADR-0044의 premise가 그 수를 센다).
     *
     * @return 실제로 바뀐 행 수. 업종이 안 바뀌었으면 0이 정상이다.
     */
    @Transactional
    public long collect(SecurityRef security) throws DataSourceException {
        CompanyProfileDataSource source = registry.companyProfileSource(security.marketCode());
        Optional<CompanyProfile> profile = source.fetchProfile(security);
        if (profile.isEmpty()) {
            return 0L;
        }
        CompanyProfile found = profile.get();

        if (found.scheme() == null) {
            // 레코드의 압축 생성자가 "코드가 있으면 체계도 있다"를 이미 강제하므로 여기 오면
            // 코드가 비어 있다. 포트 계약상 그때는 Optional.empty()라야 한다 — 조용히
            // 넘기면 어댑터의 계약 위반이 영영 안 드러난다.
            throw new IllegalStateException(
                    source.sourceName() + "가 업종코드 없는 개황을 냈다. ticker=" + security.ticker()
                            + " — 코드가 비면 Optional.empty()여야 한다");
        }

        // IS DISTINCT FROM인 이유는 IndicatorValueWriter와 같다 — 값이 그대로면 updated_at까지
        // 건드리지 않아야 "언제 실제로 바뀌었나"가 남는다. 업종은 거의 바뀌지 않으므로
        // 재실행에서 0행이 정상이고, 그 0이 곧 멱등의 증거다.
        int updated = jdbcTemplate.update(updateFor(found.scheme()),
                found.industryCode(), found.accountingMonth(), security.stockId(),
                found.industryCode(), found.accountingMonth());

        if (updated > 0) {
            log.info("업종을 채웠다. ticker={} scheme={} code={} acc_mt={}",
                    security.ticker(), found.scheme(), found.industryCode(), found.accountingMonth());
        }
        return updated;
    }

    /**
     * 체계별 UPDATE. <b>switch가 완전해야 한다</b> — 체계가 늘면 여기서 컴파일이 깨지고,
     * 그것이 새 체계를 기존 컬럼에 밀어 넣지 않게 만드는 자리다.
     */
    private static String updateFor(IndustryScheme scheme) {
        return switch (scheme) {
            case KSIC -> UPDATE_KSIC;
            case SIC -> UPDATE_SIC;
        };
    }

    private static final String UPDATE_KSIC = """
            UPDATE stock
               SET industry_code    = ?,
                   accounting_month = ?,
                   updated_at       = now()
             WHERE id = ?
               AND (industry_code    IS DISTINCT FROM ?
                 OR accounting_month IS DISTINCT FROM ?)
            """;

    private static final String UPDATE_SIC = """
            UPDATE stock
               SET sic_code         = ?,
                   accounting_month = ?,
                   updated_at       = now()
             WHERE id = ?
               AND (sic_code         IS DISTINCT FROM ?
                 OR accounting_month IS DISTINCT FROM ?)
            """;
}
