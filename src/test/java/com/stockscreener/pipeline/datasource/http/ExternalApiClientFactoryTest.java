package com.stockscreener.pipeline.datasource.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.stockscreener.pipeline.datasource.http.ExternalApiClientFactory.normalizeServiceKey;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * serviceKey 이중 인코딩 방지를 검증한다.
 *
 * <p>공공데이터포털이 발급하는 키는 이미 URL 인코딩되어 있다. URI 빌더가 다시 인코딩하면
 * {@code %2F}가 {@code %252F}가 되어 인증에 실패하는데, 그 실패는 "등록되지 않은 서비스키"로
 * 보여 키 자체가 잘못된 것으로 오해하게 된다. 실측에서 실제로 겪은 함정이다.
 */
class ExternalApiClientFactoryTest {

    @Test
    @DisplayName("인코딩된 키를 디코딩한다 — 빌더가 다시 인코딩해야 원래 값이 된다")
    void decodesEncodedKey() {
        String issued = "abc%2Fdef%3D%3D";

        assertThat(normalizeServiceKey(issued)).isEqualTo("abc/def==");
    }

    @Test
    @DisplayName("이미 디코딩된 키는 그대로 둔다 — 어느 형태로 넣어도 안전해야 한다")
    void leavesDecodedKeyUnchanged() {
        String plain = "abc/def==";

        assertThat(normalizeServiceKey(plain)).isEqualTo(plain);
    }

    @Test
    @DisplayName("빈 키는 빈 문자열이다 — 키 없이도 기동해야 하므로 예외를 던지지 않는다")
    void handlesMissingKey() {
        assertThat(normalizeServiceKey(null)).isEmpty();
        assertThat(normalizeServiceKey("  ")).isEmpty();
    }

    @Test
    @DisplayName("실제 발급 키 형태를 디코딩한다")
    void decodesRealWorldKeyShape() {
        // 발급 키와 같은 형태(URL-safe Base64 + %3D%3D 종결)를 흉내낸 합성 값이다.
        // MUST NOT: 실제 키를 테스트에 넣지 않는다 — 테스트 파일은 커밋된다.
        String issued = "AAAAbbbbCCCCddddEEEEffffGGGGhhhh%2FiiiiJJJJkkkkLLLLmmmm%3D%3D";

        String normalized = normalizeServiceKey(issued);

        assertThat(normalized).endsWith("==").contains("/");
        assertThat(normalized).doesNotContain("%2F").doesNotContain("%3D");
    }
}
