package com.stockscreener.pipeline.collector;

import com.stockscreener.pipeline.datasource.CorporateAction;
import com.stockscreener.pipeline.datasource.Distribution;
import com.stockscreener.pipeline.datasource.SecurityRef;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 원장 보충 — 소스가 빠뜨린 사건을 SEC 원문으로 싣는 커밋된 입력(ADR-0181 · {@code corporate-action/supplement.tsv}).
 *
 * <p>코드 갈래가 아니라 원장 입력 줄이다 — 특정 종목을 위한 분기를 만들지 않고, 근거(SEC 접수번호와 원문 문장)가 없는 줄은 기동을 세운다.
 * 수집({@link CorporateActionCollector#collect})이 소스 응답에 합쳐 {@code SOURCE_DISCLOSURE}로 적고, 그 뒤는 소스 사건과 같은 경로다(자식 가치
 * 비율 · 적용 직전 가격 반증). 구성원(실측): UHAL Series N 9:1 · SPHR 2023-04-21 분사 · CORZ 파산 교환 0.1 + 워런트.
 */
@Component
public class CorporateActionSupplement {

    static final String FILE = "corporate-action/supplement.tsv";

    private final Map<PriceObservationWindow.AnchorKey, List<CorporateAction>> byAnchor;

    public CorporateActionSupplement() {
        this(load(FILE));
    }

    CorporateActionSupplement(Map<PriceObservationWindow.AnchorKey, List<CorporateAction>> byAnchor) {
        this.byAnchor = Map.copyOf(byAnchor);
    }

    /** 그 행의 보충 사건 — 닻이 없으면 없다. */
    public List<CorporateAction> forSecurity(SecurityRef security) {
        SecurityRef.Identity identity = security.identity();
        if (identity.anchorSymbol() == null) {
            return List.of();
        }
        return byAnchor.getOrDefault(new PriceObservationWindow.AnchorKey(security.marketCode(), identity.anchorSymbol(),
                identity.anchorDate()), List.of());
    }

    int size() {
        return byAnchor.values().stream().mapToInt(List::size).sum();
    }

    static Map<PriceObservationWindow.AnchorKey, List<CorporateAction>> load(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("원장 보충 파일이 없다: " + path);
        }
        Map<PriceObservationWindow.AnchorKey, List<CorporateAction>> out = new HashMap<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int number = 0;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] p = line.split("\t", -1);
                if (p.length != 11 || p[9].isBlank() || p[10].isBlank()) {
                    // 근거 없는 사건은 받지 않는다 — 원장 행의 출처를 아무도 되짚을 수 없다.
                    throw new IllegalStateException("원장 보충 파일 %s %d행: 열 열하나와 접수번호 · 근거가 필요하다 — %s".formatted(path, number, line));
                }
                PriceObservationWindow.AnchorKey key = new PriceObservationWindow.AnchorKey(p[0].trim(), p[1].trim(), LocalDate.parse(p[2].trim()));
                LocalDate effective = LocalDate.parse(p[3].trim());
                CorporateAction.Type type = CorporateAction.Type.valueOf(p[4].trim());
                BigDecimal ratio = new BigDecimal(p[5].trim());
                String form = p[8].trim() + " " + p[9].trim();
                CorporateAction action;
                if (type == CorporateAction.Type.SPIN_OFF) {
                    LocalDate childAsOf = LocalDate.parse(p[7].trim());
                    List<Distribution.Child> children = new ArrayList<>();
                    for (String child : p[6].split(",")) {
                        String[] parts = child.trim().split(":");
                        if (parts.length != 2) {
                            throw new IllegalStateException("원장 보충 파일 %s %d행: 자식은 기호:비율이다 — %s".formatted(path, number, child));
                        }
                        children.add(new Distribution.Child(parts[0].trim(), null, new BigDecimal(parts[1].trim()), childAsOf));
                    }
                    action = CorporateAction.unresolvedSpinOff(effective, effective, ratio,
                            new Distribution(children, effective, null, null), form);
                } else {
                    action = new CorporateAction(effective, effective, effective, type, ratio,
                            CorporateAction.DetectionSource.SOURCE_DISCLOSURE, form);
                }
                out.computeIfAbsent(key, k -> new ArrayList<>()).add(action);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("원장 보충 파일을 못 읽었다: " + path, e);
        }
        return out;
    }
}
