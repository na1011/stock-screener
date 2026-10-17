package com.stockscreener.pipeline.datasource.us;

import com.stockscreener.pipeline.datasource.us.listing.ClassLines;
import com.stockscreener.pipeline.datasource.us.listing.ListingFormReader;
import com.stockscreener.pipeline.datasource.us.listing.Venue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 받아 둔 거래소 양식 본문을 판독기에 다시 대는 0콜 재생 — 드라이런(R-D5)이 「못 읽음」으로 남긴 양식을 고친 판독기로 다시 잰다.
 * 파일 이름은 {@code <양식>-<CIK>-<접수번호>.doc}(양식의 빗금은 밑줄)이고 본문은 SEC가 준 그대로다.
 *
 * <p>칠 때: {@code LISTING_REPLAY_DIR=<디렉터리> ./gradlew test --tests '*ListingFormReplayTest*' -PskipFrontend -i}
 */
@EnabledIfEnvironmentVariable(named = "LISTING_REPLAY_DIR", matches = ".+")
class ListingFormReplayTest {

    @Test
    void replay() throws Exception {
        Path dir = Path.of(System.getenv("LISTING_REPLAY_DIR"));
        int read = 0;
        int unread = 0;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".doc")).sorted().toList()) {
                String name = file.getFileName().toString();
                String form = name.startsWith("25-NSE-") ? "25-NSE" : name.startsWith("8-A12B-") ? "8-A12B" : "25";
                byte[] body = SecListingFormClient.stripInjected(Files.readAllBytes(file));
                String why;
                String what;
                if ("8-A12B".equals(form)) {
                    ListingFormReader.Registration r = ListingFormReader.readRegistration(new String(body, StandardCharsets.ISO_8859_1));
                    why = r.unreadable();
                    what = r.rows().stream().map(x -> x.venue().map(Venue::name).orElse("?") + "=" + x.line()
                            + (ClassLines.isCommon(x.line()) ? "[보통주]" : "")).toList().toString();
                } else {
                    ListingFormReader.Removal r = "25-NSE".equals(form)
                            ? ListingFormReader.readExchangeRemoval(body, name)
                            : ListingFormReader.readIssuerRemoval(new String(body, StandardCharsets.ISO_8859_1));
                    why = r.unreadable() != null ? r.unreadable() : r.venue().isEmpty() ? "거래소 모름: " + r.rawVenue() : null;
                    what = r.venue().map(Venue::name).orElse("?") + " " + r.lines();
                }
                if (why == null) {
                    read++;
                } else {
                    unread++;
                }
                System.out.println("REPLAY " + name + " · " + (why == null ? "읽음 " + what : "못 읽음 " + why));
            }
        }
        System.out.println("REPLAY 합계 읽음=" + read + " 못 읽음=" + unread);
    }
}
