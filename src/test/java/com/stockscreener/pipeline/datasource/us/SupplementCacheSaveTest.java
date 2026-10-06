package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 같은 날 원자료 수집기({@link SecFilingSupplementCacheLiveTest})의 저장 — 같은 디렉터리에 다시 모으면 낡은 {@code .status}가 지워져야
 * 한다. 재생({@link SupplementReplay})은 {@code .status}를 본문보다 먼저 보므로, 남으면 다시 모아 {@code failures=0}인데도 재생이 그
 * 공시를 받기 실패로 읽어 손 FR 2-5가 거짓으로 멈춘다(2026-10-07 v14 반증 재현).
 */
class SupplementCacheSaveTest {

    @Test
    @DisplayName("2xx를 저장하면 그 경로의 낡은 .status를 지운다 — 다시 모은 디렉터리를 재생이 실패로 읽지 않는다")
    void savingABodyClearsAStaleStatus(@TempDir Path dir) throws Exception {
        Path save = dir.resolve("sec/Archives/edgar/data/1800/000000/index.json");
        Files.createDirectories(save.getParent());
        Files.writeString(SecFilingSupplementCacheLiveTest.statusOf(save), "503");

        SecFilingSupplementCacheLiveTest.saveBody(save, "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(Files.readString(save)).isEqualTo("{}");
        assertThat(SecFilingSupplementCacheLiveTest.statusOf(save)).doesNotExist();
    }
}
