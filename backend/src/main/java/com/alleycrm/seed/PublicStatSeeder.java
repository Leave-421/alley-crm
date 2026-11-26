package com.alleycrm.seed;

import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * ④ 공공데이터 기본값(E2 #16)을 표 public_stat에 넣는다. 표 자체가 외부 공개 통계(NFR-10 #3, {@code dataSource = public})이고,
 * 행마다 출처 · 기준 기간을 남겨 화면이 표시한다(NFR-10 #2).
 */
@Component
@Profile("seed")
class PublicStatSeeder {

    private final JdbcTemplate jdbc;

    PublicStatSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 넣은 행 수. */
    int insert(List<PublicStatFile.Row> rows) {
        jdbc.batchUpdate("""
                INSERT INTO public_stat (kind, category_from, category_to, slot, gender, age_group, value, source, period)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows.stream().map(r -> new Object[] {
                        r.kind().code(), r.categoryFrom(), r.categoryTo(), r.slot(), r.gender(), r.ageGroup(), r.value(),
                        r.source(), r.period()})
                .toList());
        return rows.size();
    }
}
