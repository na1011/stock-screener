package com.stockscreener.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 종목의 <b>한국어 별칭</b>. 미국 종목을 「마이크로소프트」로 찾게 하는 것이 전부다.
 *
 * <p><b>왜 DB가 아니라 리소스인가</b>: 이 데이터는 회사 이름이라 거의 안 바뀌고, 없어도
 * 영문 검색이 그대로 동작한다(퇴화가 안전하다). 테이블을 만들면 마이그레이션과 시더가
 * 따라오는데 그만한 하중이 아니다.
 *
 * <p><b>왜 {@code stock.name_en}을 안 쓰는가</b>: 그 컬럼은 <b>전 시장 전 행이 NULL</b>이고
 * {@link StockReader#search}가 아예 보지 않는다. 그리고 한 종목에 별칭이 여럿이라
 * (「마소」·「마이크로소프트」) 컬럼 하나로는 담기지 않는다. <b>그 컬럼을 채워도 검색은
 * 안 바뀐다</b> — 다음 세션이 여기서 헤매지 않도록 적어 둔다.
 *
 * <p><b>MUST: 검색어와 별칭을 NFC로 정규화해 맞춘다.</b> {@code 마이크로소프트}(7자)와 그
 * NFD(14자)는 <b>다른 문자열</b>이고, 인메모리 키 매칭은 거기서 조용히 0건이 된다. 지금
 * 저장된 데이터는 양쪽 다 NFC지만(국내 종목명 368/368 · 이 파일 전량) <b>막아야 할 것은
 * 사용자 입력 쪽</b>이다.
 *
 * <p>CSV 형식은 {@code market_code,ticker,alias}이고 {@code #}로 시작하는 줄은 주석이다.
 * <b>{@code SeededUniverseDataSource}의 파싱을 베끼지 않는다</b> — 그쪽은 첫 줄을 무조건
 * 헤더로 버리는데 실제 파일의 첫 줄이 주석이라 진짜 헤더가 데이터로 들어간다.
 */
@Component
public class StockAliasIndex {

    private static final Logger log = LoggerFactory.getLogger(StockAliasIndex.class);

    private static final String DEFAULT_PATH = "stock-alias/us-ko.csv";

    /**
     * 한 질의가 낼 수 있는 종목 수의 상한. 넘으면 접두 매칭만 남긴다.
     *
     * <p>실측으로 가장 흔한 한 글자가 {@code 스}이고 137종목이 걸린다 — PostgreSQL에
     * 부담이 아니라서 <b>이 상한은 성능이 아니라 방어용</b>이다. 별칭이 크게 늘어도
     * {@code IN} 절이 무한정 자라지 않게 한다.
     */
    private static final int MAX_MATCHES = 200;

    /** 정규화한 별칭 → 그 별칭을 가진 (시장, 티커). */
    private final Map<String, Set<MarketTicker>> byAlias;

    /**
     * MUST — <b>생성자를 하나만 둔다.</b> 둘이면 Spring이 인자 없는 쪽을 골라
     * {@code stock.alias-path}가 <b>조용히 무시된다</b>(시험이 운영 CSV를 읽어
     * 별칭 0건으로 붉었다). {@code UsMarketCapUniverseAdapter}가 같은 함정을
     * javadoc에 적어 둔 것과 같은 자리다.
     */
    StockAliasIndex(@Value("${stock.alias-path:" + DEFAULT_PATH + "}") String path) {
        this.byAlias = load(path);
        log.info("종목 별칭을 읽었다. path={} 별칭={}개", path, byAlias.size());
    }

    /** 시장과 티커의 쌍. <b>티커는 시장 안에서만 유일하다</b>({@code uq_stock_market_ticker}). */
    public record MarketTicker(String marketCode, String ticker) {
    }

    /** 접두로 걸린 것과 포함으로 걸린 것. 검색의 정렬 등급이 둘을 다르게 다룬다. */
    public record Matches(List<MarketTicker> prefix, List<MarketTicker> contains) {

        public boolean isEmpty() {
            return prefix.isEmpty() && contains.isEmpty();
        }
    }

    /**
     * 검색어에 걸리는 종목. <b>접두와 포함을 갈라 낸다.</b>
     *
     * <p>상한을 넘으면 <b>포함을 버리고 접두만</b> 남긴다 — 자르는 것이 「무엇이 잘렸는지
     * 모르게」가 아니라 「덜 관련된 쪽부터」이기 위해서다.
     */
    public Matches match(String query) {
        String needle = normalize(query);
        if (needle.isEmpty()) {
            return new Matches(List.of(), List.of());
        }
        Set<MarketTicker> prefix = new LinkedHashSet<>();
        Set<MarketTicker> contains = new LinkedHashSet<>();
        for (Map.Entry<String, Set<MarketTicker>> e : byAlias.entrySet()) {
            if (e.getKey().startsWith(needle)) {
                prefix.addAll(e.getValue());
            } else if (e.getKey().contains(needle)) {
                contains.addAll(e.getValue());
            }
        }
        contains.removeAll(prefix);
        if (prefix.size() + contains.size() > MAX_MATCHES) {
            return new Matches(List.copyOf(prefix), List.of());
        }
        return new Matches(List.copyOf(prefix), List.copyOf(contains));
    }

    /** MUST: NFC로 맞춘다. 그러지 않으면 같은 글자가 다른 키가 된다. */
    private static String normalize(String raw) {
        return Normalizer.normalize(raw.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<MarketTicker>> load(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            // MUST NOT: 조용히 빈 채로 두지 않는다. 별칭이 없으면 한글 검색이 통째로
            // 안 되는데, 그것은 「일치하는 종목이 없다」와 화면에서 구별되지 않는다.
            throw new IllegalStateException("종목 별칭 파일이 없다: " + path);
        }
        Map<String, Set<MarketTicker>> index = new LinkedHashMap<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split(",", 3);
                if (parts.length < 3) {
                    continue;
                }
                if ("market_code".equals(parts[0])) {
                    continue;   // 헤더. 첫 줄이 주석이라 순서로 판정할 수 없다.
                }
                String alias = normalize(parts[2]);
                if (alias.isEmpty()) {
                    continue;
                }
                index.computeIfAbsent(alias, k -> new LinkedHashSet<>())
                        .add(new MarketTicker(parts[0].trim(), parts[1].trim()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("종목 별칭 파일을 읽지 못했다: " + path, e);
        }
        return index;
    }

    /** 시험이 인덱스 크기를 확인하는 자리. */
    int size() {
        return byAlias.size();
    }

    /** 별칭 하나가 가리키는 종목들. 시험용. */
    List<MarketTicker> lookup(String alias) {
        return new ArrayList<>(byAlias.getOrDefault(normalize(alias), Set.of()));
    }
}
