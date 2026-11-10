package com.stockscreener.pipeline.datasource.kr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 운영 클라이언트({@link DartViewerClient})가 받은 원문이 해석기 시험의 원문(다른 클라이언트로 받은 그대로 · {@code dart-prov/})과 바이트까지
 * 같은가 — 요청 머리가 달라 글자가 깨져 오는 응답(curl 기본 머리 · U+FFFD)이 있었다. 4콜 · 1초 1건.
 */
@Tag("live")
class DartViewerClientLiveTest {

    @Test
    @DisplayName("정정 잠정(삼성전자 3분기)과 I001 원문이 시험 원문과 같고 서식으로 읽힌다")
    void sameBytesAsTheFixtures() throws Exception {
        DartViewerClient client = new DartViewerClient();
        for (String rceptNo : new String[]{"20261029800099", "20260120800764"}) {
            byte[] live = client.document(rceptNo);
            try (InputStream in = getClass().getResourceAsStream("/dart-prov/" + rceptNo + ".html")) {
                assertThat(live).as(rceptNo).isEqualTo(in.readAllBytes());
            }
            assertThat(DartViewerClient.containsReplacementCharacter(live)).isFalse();
        }
    }
}
