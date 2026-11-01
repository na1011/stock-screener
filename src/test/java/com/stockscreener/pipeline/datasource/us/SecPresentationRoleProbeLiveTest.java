package com.stockscreener.pipeline.datasource.us;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>탐침</b> — 10-K의 표시 링크베이스({@code _pre.xml})에서 {@code OperatingIncomeLoss}가 어느 역할(손익계산서 · 부문 주석)에 실리는가(재고
 * 「부문 합계가 영업이익 자리에 앉음」). {@code PRE_PROBE}에 「기호:CIK」 쉼표 목록 · 10-K마다 2~3콜 · 초당 5회 아래.
 *
 * <p>MUST NOT: {@code SEC_EDGAR_USER_AGENT}를 출력에 찍지 않는다 — 이메일이 들어 있다.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "SEC_EDGAR_USER_AGENT", matches = ".+", disabledReason = "SEC_EDGAR_USER_AGENT가 없어 건너뛴다")
class SecPresentationRoleProbeLiveTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    private String get(String url) throws Exception {
        Thread.sleep(220);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", System.getenv("SEC_EDGAR_USER_AGENT")).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " " + url);
        }
        return res.body();
    }

    @Test
    @DisplayName("[live][probe] 10-K 표시 링크베이스에서 OperatingIncomeLoss가 실린 역할")
    void rolesOfOperatingIncome() throws Exception {
        int years = Integer.parseInt(System.getenv().getOrDefault("PRE_PROBE_YEARS", "3"));
        for (String pair : System.getenv().getOrDefault("PRE_PROBE", "MSFT:789019").split(",")) {
            String[] tc = pair.split(":");
            long cik = Long.parseLong(tc[1]);
            JsonNode recent = json.readTree(get("https://data.sec.gov/submissions/CIK%010d.json".formatted(cik))).path("filings").path("recent");
            List<String[]> tenK = new ArrayList<>();
            for (int i = 0; i < recent.path("form").size(); i++) {
                if ("10-K".equals(recent.path("form").get(i).asString())) {
                    tenK.add(new String[]{recent.path("accessionNumber").get(i).asString(), recent.path("reportDate").get(i).asString()});
                }
            }
            for (String[] k : tenK.subList(0, Math.min(years, tenK.size()))) {
                String folder = k[0].replace("-", "");
                JsonNode items = json.readTree(get("https://www.sec.gov/Archives/edgar/data/%d/%s/index.json".formatted(cik, folder)))
                        .path("directory").path("item");
                String pre = null;
                for (JsonNode it : items) {
                    if (it.path("name").asString().endsWith("_pre.xml")) {
                        pre = it.path("name").asString();
                    }
                }
                boolean meta = false;
                boolean summary = false;
                for (JsonNode it : items) {
                    meta |= it.path("name").asString().equals("MetaLinks.json");
                    summary |= it.path("name").asString().equals("FilingSummary.xml");
                }
                System.out.println(tc[0] + " " + k[1] + " 재료 MetaLinks=" + meta + " FilingSummary=" + summary + " pre=" + (pre != null));
                if (meta && System.getenv("PRE_PROBE_META") != null) {
                    JsonNode m = json.readTree(get("https://www.sec.gov/Archives/edgar/data/%d/%s/MetaLinks.json".formatted(cik, folder)));
                    JsonNode inst = m.path("instance").properties().iterator().next().getValue();
                    JsonNode tag = inst.path("tag").path("us-gaap_OperatingIncomeLoss");
                    List<String> faceRoles = new ArrayList<>();
                    for (JsonNode role : tag.path("presentation")) {
                        for (var r : inst.path("report").properties()) {
                            if (r.getValue().path("role").asString().equals(role.asString())) {
                                faceRoles.add(r.getValue().path("groupType").asString() + ":" + role.asString().substring(role.asString().lastIndexOf('/') + 1));
                            }
                        }
                    }
                    System.out.println(tc[0] + " " + k[1] + " MetaLinks OperatingIncomeLoss 역할=" + faceRoles);
                }
                if (pre == null) {
                    continue;
                }
                String body = get("https://www.sec.gov/Archives/edgar/data/%d/%s/%s".formatted(cik, folder, pre));
                Matcher link = Pattern.compile("<link:presentationLink[^>]*xlink:role=\"([^\"]+)\"(.*?)</link:presentationLink>", Pattern.DOTALL)
                        .matcher(body);
                List<String> roles = new ArrayList<>();
                while (link.find()) {
                    if (link.group(2).contains("_OperatingIncomeLoss\"")) {
                        roles.add(link.group(1).substring(link.group(1).lastIndexOf('/') + 1));
                    }
                }
                System.out.println(tc[0] + " " + k[1] + " " + pre + " OperatingIncomeLoss 역할=" + roles);
            }
        }
    }
}
