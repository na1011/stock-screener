package com.stockscreener.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * 표 행의 물리 판({@code ctid · xmin · xmax}) — 「같은 값을 다시 쓰면 WAL을 안 낸다」를 <b>그 시험이 쓴 행만으로</b> 잰다.
 *
 * <p>WAL을 내는 쓰기는 행에 흔적을 남긴다. 갱신은 새 판(ctid · xmin)을, 삭제는 행의 사라짐을, 거짓 {@code WHERE}의
 * {@code ON CONFLICT DO UPDATE}가 거는 행 잠금은 {@code xmax}를 남긴다 — 같은 트랜잭션이 넣은 행에도 남는다(2026-10-31 PG 16.14
 * 임시 표 실측: 같은 트랜잭션 {@code xmin = xmax}). 그래서 다시 쓰기 전후의 판이 같고 {@code xmax}가 0이면 그 쓰기는 이 표에 WAL을
 * 낼 일을 안 했다.
 *
 * <p>클러스터 WAL 위치({@code pg_current_wal_insert_lsn})로 재면 같은 클러스터의 운영 앱 · autovacuum이 쓴 바이트가 섞이는데 단언이
 * 「0바이트」라 여유가 0이다 — 2026-10-31 화해 뒤 autovacuum과 겹쳐 둘이 붉었고 다시 돌리면 초록이었다. PG 16에는 백엔드 하나의 WAL
 * 계수기가 없다.
 */
public final class RowVersions {

    private RowVersions() {
    }

    /** 그 표의 보이는 행 전부의 판, ctid 순. */
    public static List<String> of(JdbcTemplate jdbc, String table) {
        return jdbc.queryForList("SELECT ctid::text || ' ' || xmin::text || ' ' || xmax::text FROM " + table + " ORDER BY ctid",
                String.class);
    }

    /** 잠기지도 지워지지도 않은 판인가 — {@code xmax}가 0. 이미 잠긴 행은 두 번째 잠금이 {@code xmax}를 안 바꿔 전후 대조가 못 본다. */
    public static boolean unlocked(String version) {
        return version.endsWith(" 0");
    }
}
