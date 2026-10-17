package com.stockscreener.pipeline.datasource.us;

import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;

/**
 * companyfacts가 싣는 접수번호 전부 — 택소노미를 안 가린다(us-gaap · dei · ifrs-full). 등록인 포함 검사(ADR-0180)의 재료다: 그 행의
 * 저장 접수번호가 이 집합(∪ 같은 CIK 보충 표)에 다 들면 그 행은 이 등록인의 것이다.
 *
 * <p>공시 보충 판독기({@link SecFilingSupplement})와 따로 둔다 — 그 파일은 판 범위라(ADR-0173 · {@code ReaderVersionPinTest}) 한 토큰만
 * 바꿔도 판을 올려 저장 보충을 전부 다시 읽는다. 이 함수는 판독과 무관하다.
 */
final class CompanyFactsAccessions {

    private CompanyFactsAccessions() {
    }

    /** @param facts companyfacts의 {@code facts} 노드(택소노미 → 개념 → 단위 → 팩트) */
    static Set<String> of(JsonNode facts) {
        Set<String> out = new HashSet<>();
        for (String taxonomy : facts.propertyNames()) {
            JsonNode concepts = facts.path(taxonomy);
            for (String concept : concepts.propertyNames()) {
                JsonNode units = concepts.path(concept).path("units");
                for (String unit : units.propertyNames()) {
                    for (JsonNode node : units.path(unit)) {
                        String accn = node.path("accn").asString("");
                        if (!accn.isEmpty()) {
                            out.add(accn);
                        }
                    }
                }
            }
        }
        return out;
    }
}
