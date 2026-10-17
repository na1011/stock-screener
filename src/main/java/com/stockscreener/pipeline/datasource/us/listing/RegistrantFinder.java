package com.stockscreener.pipeline.datasource.us.listing;

import com.stockscreener.pipeline.datasource.DataSourceException;
import com.stockscreener.pipeline.datasource.RegistrantLookup.Rename;
import com.stockscreener.pipeline.datasource.us.SecListingFormClient.Cover;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 폐지 실체(또는 오늘 SEC 파일에 없는 행)의 등록인 찾기 — 후보는 둘, 확인은 하나다(탐색 {@code US-PAST-LISTING-IMPL-1018.md} §2.2).
 *
 * <ul>
 *   <li>후보 (a) 이름 — 증권 이름을 정기 공시 제출자 이름과 대조(재사용 기호에서 시세 소스 이름은 오늘 주인의 것이라 부르는 쪽이
 *       그때는 이름을 안 준다).</li>
 *   <li>후보 (b) 효력일 무렵 그 실체의 보통주 제거 양식을 낸 등록인 — 이름 없이 닿는 길이다(탐색 ★17: 문턱 위 35 중 34).</li>
 *   <li><b>확인</b> — 사건 전(효력일 전날까지) 마지막 정기 공시 표지에 <b>그 공시일의 기호</b> 줄이 있다. 그 공시일의 기호는 개명을
 *       거꾸로 걸어 정한다(RNA → RNAM · 탐색 ★6). 줄의 거래소가 그 실체의 그날 범주다.</li>
 * </ul>
 *
 * <p>정밀도는 확인 하나가 정하고 두 출처는 재현율만 바꾼다 — 어느 출처의 「없음」도 판정에 안 쓴다. 확인된 등록인이 둘 이상이면
 * 묶지 않고 센다(모회사 + 운영 파트너십 · HOUS · VRE). 순수 판정이다 — 표지 받기는 부르는 쪽이 넘긴다.
 */
public final class RegistrantFinder {

    private RegistrantFinder() {
    }

    /** 사건 전 마지막 정기 공시 표지(없으면 빈 값). */
    @FunctionalInterface
    public interface Covers {
        Optional<Cover> lastBefore(long cik, LocalDate before) throws DataSourceException;
    }

    public enum Result { BOUND, NONE, AMBIGUOUS }

    /**
     * @param cik     {@link Result#BOUND}일 때만
     * @param line    확인한 표지 줄(그 공시일 기호 · 거래소 · 증권 이름)
     * @param tried   대조한 후보와 그 결과(실행 기록의 귀속 재료)
     */
    public record Decision(Result result, Long cik, Cover cover, ListingCover.Line line, List<String> tried) {
    }

    /**
     * @param symbol   사건 날의 기호
     * @param before   표지를 찾을 끝(제외) — 효력일
     * @param byName   이름 대조 후보(없으면 빈 목록)
     * @param byForm   제거 양식 후보(없으면 빈 목록)
     * @param renames  그 실체에 걸린 개명들(기호 걷기 재료)
     */
    public static Decision find(String symbol, LocalDate before, List<Long> byName, List<Long> byForm, List<Rename> renames,
                                Covers covers) throws DataSourceException {
        Set<Long> candidates = new LinkedHashSet<>(byName);
        candidates.addAll(byForm);
        List<String> tried = new ArrayList<>();
        List<Decision> confirmed = new ArrayList<>();
        for (long cik : candidates) {
            String source = (byName.contains(cik) ? "이름" : "") + (byForm.contains(cik) ? "양식" : "");
            Optional<Cover> cover = covers.lastBefore(cik, before);
            if (cover.isEmpty()) {
                tried.add(cik + "(" + source + ") 표지 없음");
                continue;
            }
            String then = symbolOn(symbol, cover.get().filed(), renames);
            Optional<ListingCover.Line> line = ListingCover.lineFor(cover.get().facts(), then);
            if (line.isEmpty()) {
                tried.add(cik + "(" + source + ") " + cover.get().form() + " " + cover.get().filed() + " 표지에 " + then + " 줄 없음 "
                        + ListingCover.lines(cover.get().facts()).stream().map(ListingCover.Line::symbol).toList());
                continue;
            }
            tried.add(cik + "(" + source + ") " + cover.get().form() + " " + cover.get().filed() + " 표지 " + line.get().symbol()
                    + " · " + line.get().venueCode());
            confirmed.add(new Decision(Result.BOUND, cik, cover.get(), line.get(), tried));
        }
        if (confirmed.size() == 1) {
            Decision only = confirmed.get(0);
            return new Decision(Result.BOUND, only.cik(), only.cover(), only.line(), List.copyOf(tried));
        }
        return new Decision(confirmed.isEmpty() ? Result.NONE : Result.AMBIGUOUS, null, null, null, List.copyOf(tried));
    }

    /** {@code date}에 그 실체가 쓰던 기호 — 개명을 효력일 역순으로 거꾸로 건다. */
    static String symbolOn(String symbol, LocalDate date, List<Rename> renames) {
        String current = symbol;
        List<Rename> ordered = renames.stream().sorted((a, b) -> b.date().compareTo(a.date())).toList();
        for (Rename r : ordered) {
            if (r.to().equals(current) && date.isBefore(r.date())) {
                current = r.from();
            }
        }
        return current;
    }
}
