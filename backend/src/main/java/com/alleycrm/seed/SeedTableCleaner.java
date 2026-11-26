package com.alleycrm.seed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 시드 · 시연 데이터를 지운다. 지울 표와 순서는 실제 DB의 외래 키에서 매번 계산한다.
 * 그래서 D1 · D19가 표를 더해도 이 목록을 손으로 고칠 필요가 없고, 다른 사람의 마이그레이션 PR을 막지 않는다.
 *
 * <ol>
 *   <li>비울 수 있는 외래 키 칸은 먼저 모두 {@code NULL}로 비운다. 자기 참조(다음 코스의 기준 예약)와
 *       표끼리의 순환(예약 ↔ 정식 코스 제안)이 이것으로 풀린다.</li>
 *   <li>남은 비울 수 없는 외래 키만 보고, 다른 표가 가리키는 표는 가리키는 표를 먼저 지운 뒤 지운다.</li>
 * </ol>
 * {@code FOREIGN_KEY_CHECKS}를 끄지 않고, {@code TRUNCATE}(암묵적 커밋이라 롤백 안 됨)를 쓰지 않는다.
 * 호출하는 쪽의 트랜잭션 안에서 {@code UPDATE} · {@code DELETE}만 한다.
 * {@link #KEEP}의 표는 지우지 않는다. D1 · D19 마이그레이션은 행을 넣지 않으므로(#120 답) 지금은 Flyway 기록만 남긴다.
 * 상인회가 바꾼 설정값({@code setting}) · 공유 설정({@code share_policy})도 지워져 기본값으로 돌아간다.
 */
@Component
@Profile("seed")
class SeedTableCleaner {

    static final Set<String> KEEP = Set.of("flyway_schema_history");

    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_]+");

    private final JdbcTemplate jdbc;

    SeedTableCleaner(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 지운 표 이름을 지운 순서대로 돌려준다. */
    List<String> deleteAll() {
        List<String> tables = jdbc.queryForList("""
                SELECT TABLE_NAME FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'
                """, String.class);
        List<ForeignKey> foreignKeys = jdbc.query("""
                SELECT k.TABLE_NAME, k.COLUMN_NAME, k.REFERENCED_TABLE_NAME, c.IS_NULLABLE
                FROM information_schema.KEY_COLUMN_USAGE k
                JOIN information_schema.COLUMNS c
                  ON c.TABLE_SCHEMA = k.TABLE_SCHEMA AND c.TABLE_NAME = k.TABLE_NAME AND c.COLUMN_NAME = k.COLUMN_NAME
                WHERE k.TABLE_SCHEMA = DATABASE() AND k.REFERENCED_TABLE_NAME IS NOT NULL
                """, (rs, i) -> new ForeignKey(
                rs.getString(1), rs.getString(2), rs.getString(3), "YES".equals(rs.getString(4))));

        List<String> order = deletionOrder(tables, foreignKeys);
        for (ForeignKey fk : foreignKeys) {
            if (fk.nullable() && order.contains(fk.table())) {
                jdbc.update("UPDATE " + quoted(fk.table()) + " SET " + quoted(fk.column()) + " = NULL"
                        + " WHERE " + quoted(fk.column()) + " IS NOT NULL");
            }
        }
        for (String table : order) {
            jdbc.update("DELETE FROM " + quoted(table));
        }
        return order;
    }

    /**
     * 지울 순서: 비울 수 없는 외래 키로 아무 표도 가리키지 않는 표부터. 같은 단계 안에서는 이름순(재현 가능).
     * 비울 수 있는 외래 키는 먼저 비우므로 순서 계산에서 뺀다. 비울 수 없는 외래 키끼리 순환하면 지우지 않고 실패한다.
     */
    static List<String> deletionOrder(List<String> tables, List<ForeignKey> foreignKeys) {
        Set<String> remaining = new TreeSet<>();
        for (String table : tables) {
            if (!KEEP.contains(table)) {
                remaining.add(table);
            }
        }
        Map<String, Set<String>> referencedBy = new HashMap<>();
        for (ForeignKey fk : foreignKeys) {
            if (fk.nullable() || !remaining.contains(fk.table()) || !remaining.contains(fk.referencedTable())) {
                continue;
            }
            if (fk.table().equals(fk.referencedTable())) {
                throw new IllegalStateException("비울 수 없는 자기 참조 칸이 있어 지울 수 없습니다: "
                        + fk.table() + "." + fk.column());
            }
            referencedBy.computeIfAbsent(fk.referencedTable(), k -> new HashSet<>()).add(fk.table());
        }

        List<String> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<String> round = new ArrayList<>();
            for (String table : remaining) {
                Set<String> children = referencedBy.getOrDefault(table, Set.of());
                if (children.stream().noneMatch(remaining::contains)) {
                    round.add(table);
                }
            }
            if (round.isEmpty()) {
                throw new IllegalStateException("비울 수 없는 외래 키끼리 순환이 있어 지울 순서를 정할 수 없습니다: " + remaining);
            }
            order.addAll(round);
            round.forEach(remaining::remove);
        }
        return order;
    }

    private static String quoted(String identifier) {
        if (!IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalStateException("예상하지 못한 표 · 칸 이름: " + identifier);
        }
        return "`" + identifier + "`";
    }

    record ForeignKey(String table, String column, String referencedTable, boolean nullable) {
    }
}
