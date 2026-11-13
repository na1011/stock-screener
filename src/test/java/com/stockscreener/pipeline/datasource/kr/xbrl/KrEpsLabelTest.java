package com.stockscreener.pipeline.datasource.kr.xbrl;

import com.stockscreener.pipeline.datasource.kr.xbrl.KrEpsLabel.Operations;
import com.stockscreener.pipeline.datasource.kr.xbrl.KrEpsLabel.ShareClass;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class KrEpsLabelTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "보통주기본주당이익|COMMON|true|TOTAL",
            "보통주의 기본주당순이익|COMMON|true|TOTAL",
            "보통주 기본/희석주당순이익|COMMON|true|TOTAL",
            "지배기업소유주에 대한 보통주 기본 및 희석주당손익|COMMON|true|TOTAL",
            "보통주희석주당이익|COMMON|false|TOTAL",
            "보통주기본주당기순손실|COMMON|true|TOTAL",
            "1. 기본주당손익|NONE|true|TOTAL",
            "기본주당이익(손실) - 계속|NONE|true|CONTINUING",
            "계속영업과 중단영업 기본 및 희석주당이익(손실)|NONE|true|TOTAL",
            "계속영업 보통주 기본주당이익(손실)|COMMON|true|CONTINUING",
            "보통주기본주당중단영업이익(손실)|COMMON|true|DISCONTINUED",
            "우선주의 기본주당순이익|PREFERRED|true|TOTAL",
            "구형우선주기본/희석주당순이익|PREFERRED|true|TOTAL",
            "1우선주기본주당이익|PREFERRED|true|TOTAL",
            "지배기업소유주에 대한 2우선주 기본 및 희석주당손익|PREFERRED|true|TOTAL",
            "우선주당순이익|PREFERRED|true|TOTAL",
            "기본주당이익(손실)|NONE|true|TOTAL",
            "주당이익|NONE|true|TOTAL",
            "지배기업지분에 대한 주당순이익|NONE|true|TOTAL",
            "희석주당이익(손실)|NONE|false|TOTAL",
            "중단영업을 포함한 주당이익|NONE|true|TOTAL",
            "계속사업주당순손실|NONE|true|CONTINUING",
    })
    void classifiesTheLedgerLabel(String label, ShareClass shareClass, boolean basic, Operations operations) {
        KrEpsLabel parsed = KrEpsLabel.of(label);

        assertThat(parsed).isNotNull();
        assertThat(parsed.shareClass()).isEqualTo(shareClass);
        assertThat(parsed.basic()).isEqualTo(basic);
        assertThat(parsed.operations()).isEqualTo(operations);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "보통주당기순이익(손실)(단위:원)",
            "보통주에 지급된 주당배당금, 중간배당",
            "희석주당이익에 대한 공시",
            "기본주당이익의 계산을 위한 분모의 조정에 대한 기술",
            "주당이익[항목]",
            "기본포괄주당이익",
            "보통주 및 우선주 기본주당이익",
            "측정기준일 현재 주당공정가치, 부여한 주식선택권",
            "대손준비금반영후희석주당조정이익(손실)",
    })
    void leavesWhatIsNotAPerShareProfitLine(String label) {
        assertThat(KrEpsLabel.of(label)).isNull();
    }

    @org.junit.jupiter.api.Test
    void takesTheOperationsAndClassTheAncestorsCarry() {
        // 부모 abstract가 「주당이익-계속영업」이고 자식은 「기본주당손익」만 적는다(NHN 꼴) — 개념 이름의 뒤 udf 번호가 조상이다.
        java.util.Map<String, String> labels = java.util.Map.of(
                "udf_IS_2_udf_IS_1_StatementOfComprehensiveIncomeAbstract", "기본주당손익",
                "udf_IS_1_StatementOfComprehensiveIncomeAbstract", "주당이익-계속영업",
                "udf_IS_4_udf_IS_3_IncomeStatementAbstract", "기본주당이익",
                "udf_IS_3_IncomeStatementAbstract", "우선주 주당이익");
        String continuing = KrEpsLabel.stored("udf_IS_2_udf_IS_1_StatementOfComprehensiveIncomeAbstract", labels);

        assertThat(continuing).isEqualTo("주당이익-계속영업 › 기본주당손익");
        assertThat(KrEpsLabel.of(continuing).operations()).isEqualTo(Operations.CONTINUING);
        assertThat(KrEpsLabel.of(KrEpsLabel.stored("udf_IS_4_udf_IS_3_IncomeStatementAbstract", labels)).shareClass())
                .isEqualTo(ShareClass.PREFERRED);
        assertThat(KrEpsLabel.stored("udf_IS_9_IncomeStatementAbstract", labels)).as("자기 이름표 없음").isNull();
        // 자기 이름표가 말하면 조상이 덮지 않는다.
        assertThat(KrEpsLabel.of("주당이익-계속영업 › 중단영업기본주당손익").operations()).isEqualTo(Operations.DISCONTINUED);
    }

    @org.junit.jupiter.api.Test
    void keepsThePreferredClassAsWritten() {
        assertThat(KrEpsLabel.of("구형우선주기본주당이익").preferredClass()).isEqualTo("구형우선주");
        assertThat(KrEpsLabel.of("보통주기본주당이익").preferredClass()).isNull();
    }
}
