package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.http.ConditionalOnApiKey;
import com.stockscreener.pipeline.datasource.us.shareclass.ClassFacts;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * SEC 공시 표지로 {@link DepositaryCovers}를 낸다 — 이름이 실린 마지막 정기 공시({@link SecListingFormClient#lastTitledCoverBefore}).
 *
 * <p>공시 목록을 거슬러 받는 하한은 기준일 2년 전이다 — 순위가 쓰는 주식수 판의 나이 상한({@code SHARES_MAX_AGE_YEARS})과 같다.
 */
@Component
@ConditionalOnApiKey("sec-edgar")
public class SecDepositaryCovers implements DepositaryCovers {

    private final SecListingFormClient forms;

    public SecDepositaryCovers(SecListingFormClient forms) {
        this.forms = forms;
    }

    @Override
    public Optional<ClassFacts> lastTitledCover(long cik, LocalDate asOf) throws DataSourceException {
        return forms.lastTitledCoverBefore(cik, asOf.plusDays(1), asOf.minusYears(UsMarketCapUniverseAdapter.SHARES_MAX_AGE_YEARS))
                .map(SecListingFormClient.Cover::facts);
    }
}
