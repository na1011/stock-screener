package com.stockscreener.pipeline.datasource.universe;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.UniverseDataSource;
import com.stockscreener.pipeline.datasource.UniverseSnapshot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 클래스패스의 시드 CSV에서 지수 구성종목을 읽는다.
 *
 * <p>이것이 <b>기본이자 영구 폴백</b>이다. 스크래핑 어댑터를 나중에 얹더라도 이 구현을
 * 지우지 않는다. 근거가 셋이다.
 * <ol>
 *   <li>코스피200·코스닥150 구성종목을 주는 공식 무료 API가 확인되지 않았다. 유일한 자동
 *       경로는 비공식 스크래핑이고, spec §7이 인용한 "회원제 전환" 전례가 정확히 그 계열이다.
 *   <li>정기 리밸런싱은 연 2회뿐이라 자동화 실패의 대가가 낮다. 반면 유니버스가 없으면
 *       다른 어떤 배치도 대상 종목을 정하지 못하므로, 이것이 파이프라인 전체의 병목이다.
 *   <li>시드가 있으면 API 키 없이도 파이프라인 전체를 구성·검증할 수 있다.
 * </ol>
 *
 * <p>CSV 형식은 {@code ticker,name} 한 줄에 한 종목이며 첫 줄은 헤더다. 이름을 함께 두는
 * 이유는 사람이 목록을 갱신할 때 티커만으로는 무엇을 고치는지 알 수 없기 때문이다.
 */
@Component
public class SeededUniverseDataSource implements UniverseDataSource {

    private static final Logger log = LoggerFactory.getLogger(SeededUniverseDataSource.class);

    private static final String SOURCE_NAME = "seed";
    private static final String BASE_PATH = "universe/";

    @Override
    public Set<String> supportedUniverseCodes() {
        return Set.of("KOSPI200", "KOSDAQ150", "SP500");
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public UniverseSnapshot fetchConstituents(String universeCode, LocalDate asOf)
            throws DataSourceException {
        String path = BASE_PATH + universeCode.toLowerCase() + ".csv";
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new DataSourceException(DataSourceException.Kind.NOT_FOUND, SOURCE_NAME,
                    "시드 파일이 없다: " + path);
        }

        List<String> tickers = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                // MUST: 티커는 문자열로 다룬다. '005930'을 숫자로 파싱하면 5930이 된다.
                String ticker = trimmed.split(",", 2)[0].strip();
                if (!ticker.isEmpty()) {
                    tickers.add(ticker);
                }
            }
        } catch (IOException e) {
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "시드 파일을 읽을 수 없다: " + path, e);
        }

        if (tickers.isEmpty()) {
            // 빈 목록은 "전 종목 제외"를 뜻하므로 스냅샷으로 만들지 않는다.
            throw new DataSourceException(DataSourceException.Kind.MALFORMED, SOURCE_NAME,
                    "시드 파일이 비어 있다: " + path);
        }

        log.debug("시드에서 구성종목 {}개를 읽었다. {} ({})", tickers.size(), universeCode, path);
        return new UniverseSnapshot(universeCode, asOf, tickers, SOURCE_NAME + ':' + universeCode);
    }
}
