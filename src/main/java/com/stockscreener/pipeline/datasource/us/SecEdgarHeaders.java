package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.http.DataSourceProperties;

import java.util.Map;

/**
 * SEC EDGAR가 요구하는 헤더. <b>인증이 아니라 신원 표시다.</b>
 *
 * <p>SEC는 신원을 밝히지 않는 자동화 도구를 「미분류 봇」으로 판정해 차단한다. HTTP 클라이언트가
 * <b>자기 기본 User-Agent를 자동으로 붙이므로</b> 반드시 명시적으로 덮어써야 한다 —
 * 안 붙이는 것이 아니라 <b>다른 값이 붙는 것</b>이 문제다.
 *
 * <p>값이 {@code Source.apiKey} 자리에 있는 이유는 {@code @ConditionalOnApiKey}가
 * {@code …<name>.api-key} 하나만 보기 때문이다({@code ConditionalOnApiKey.ApiKeyCondition}).
 * 애노테이션을 넓히면 어댑터 열둘이 쓰는 공용 기제를 호출자 하나 때문에 고치게 된다.
 * <b>여기 들어가는 것은 시크릿이 아니라 신원 표시다</b> — 값이 보여도 손해가 없고, 대신
 * 비어 있으면 안 된다.
 *
 * <p>MUST NOT: 이 값을 로그·탐침 출력·문서에 찍지 않는다. 시크릿은 아니지만 <b>이메일이 들어
 * 있고</b>, 저장소를 공개하면 그 한 번의 검색에 계정 ID가 모인다. {@code guard-secrets.sh}는
 * 경로만 보므로 막는 것이 이 규칙뿐이다.
 *
 * <p>MUST NOT: {@code Accept-Encoding}을 여기 더하지 않는다. 붙여도 본문이 정상으로 풀린다는
 * 것은 쟀지만({@code sec-edgar-financial.md}), <b>안 붙여도 되는 헤더를 붙이면 클라이언트가
 * 압축 해제를 누가 하는지에 대한 가정이 하나 늘 뿐</b>이고 얻는 것이 없다.
 */
final class SecEdgarHeaders {

    private SecEdgarHeaders() {
    }

    static Map<String, String> of(DataSourceProperties.Source source) {
        return Map.of("User-Agent", source.getApiKey());
    }
}
