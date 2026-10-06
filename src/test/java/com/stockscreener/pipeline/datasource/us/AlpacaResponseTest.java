package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.DataSourceException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatusCode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alpaca·SEC 봉투 판별의 받기 실패와 스펙 변경을 가른다(ADR-0174).
 *
 * <p>2xx인데 본문이 없거나 JSON이 아닌 것은 끊긴 전송·오류 페이지 — <b>받기 실패(TRANSIENT)</b>다. 스펙 변경(MALFORMED)은
 * 파싱된 JSON의 모양이 다를 때뿐이다. 둘을 섞으면 시장 판정이 못 받은 시장을 「소스가 답했다」로 읽는 쪽으로 기운다.
 */
class AlpacaResponseTest {

    private static final HttpStatusCode OK = HttpStatusCode.valueOf(200);

    @Test
    @DisplayName("Alpaca 200 본문 없음 · 공백 · HTML → TRANSIENT")
    void alpacaUnreadableSuccessIsTransient() {
        for (String body : new String[] {null, "", "  ", "<html><body>maintenance</body></html>", "{\"a\":"}) {
            assertThatThrownBy(() -> AlpacaResponse.requireObject(OK, body, "alpaca", "t"))
                    .as(String.valueOf(body))
                    .isInstanceOfSatisfying(DataSourceException.class,
                            e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
        }
    }

    @Test
    @DisplayName("Alpaca 200 JSON인데 모양이 다르면 MALFORMED — 스펙 변경 신호는 그대로다")
    void alpacaWrongShapeIsMalformed() {
        assertThatThrownBy(() -> AlpacaResponse.requireObject(OK, "[1,2]", "alpaca", "t"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.MALFORMED));
        assertThatThrownBy(() -> AlpacaResponse.requireCalendar(OK, "{\"x\":[]}", "alpaca", "t"))
                .isInstanceOfSatisfying(DataSourceException.class,
                        e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.MALFORMED));
    }

    @Test
    @DisplayName("408은 두 소스 모두 TRANSIENT — 「그 밖 4xx = 우리 요청 문제」에 넣지 않는다 · 다른 4xx는 그대로")
    void requestTimeoutIsTransient() {
        assertThat(AlpacaResponse.kindOf(408)).isEqualTo(DataSourceException.Kind.TRANSIENT);
        assertThat(SecEdgarResponse.kindOf(408)).isEqualTo(DataSourceException.Kind.TRANSIENT);
        assertThat(AlpacaResponse.kindOf(400)).isEqualTo(DataSourceException.Kind.MALFORMED);
        assertThat(SecEdgarResponse.kindOf(410)).isEqualTo(DataSourceException.Kind.MALFORMED);
        assertThat(SecEdgarResponse.kindOf(404)).isEqualTo(DataSourceException.Kind.NOT_FOUND);
    }

    @Test
    @DisplayName("SEC 200 본문 없음 · JSON 아님 → TRANSIENT · facts 키 없음은 MALFORMED 그대로(표지 없음 = 답 못 받음)")
    void secUnreadableSuccessIsTransient() {
        for (String body : new String[] {null, "", "<Error>x</Error>"}) {
            assertThatThrownBy(() -> SecEdgarResponse.requireJson(OK, body, "t"))
                    .as(String.valueOf(body))
                    .isInstanceOfSatisfying(DataSourceException.class,
                            e -> assertThat(e.kind()).isEqualTo(DataSourceException.Kind.TRANSIENT));
        }
        assertThatThrownBy(() -> SecEdgarResponse.requireFacts(OK, "{}", "t"))
                .isInstanceOfSatisfying(DataSourceException.class, e -> {
                    assertThat(e.kind()).isEqualTo(DataSourceException.Kind.MALFORMED);
                    assertThat(e.sourceAnswered()).as("결정성을 재지 않았다 — v9 판정표에서 뺐다").isFalse();
                });
    }
}
