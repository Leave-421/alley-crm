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
 *   <li>순서: 다른 표가 가리키는 표는 가리키는 표를 먼저 지운 뒤 지운다(모든 외래 키 기준). 순환이 있으면 그 단계만
 *       비울 수 없는 외래 키 기준으로 푼다.</li>
 *   <li>그 순서로 풀리지 않는 비울 수 있는 외래 키 칸만 먼저 {@code NULL}로 비운다: 자기 참조(다음 코스의 기준 예약)와
 *       표끼리의 순환(예약 ↔ 정식 코스 제안). 순서로 풀리는 칸은 비우지 않는다 — 비우면 CHECK 제약
 *       (예: 지갑 거래는 지갑 또는 비회원 전화번호가 있어야 함, {@code ck_wallet_tx_owner})에 걸릴 수 있다.</li>
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
        for (ForeignKey fk : columnsToClear(order, foreignKeys)) {
            jdbc.update("UPDATE " + quoted(fk.table()) + " SET " + quoted(fk.column()) + " = NULL"
                    + " WHERE " + quoted(fk.column()) + " IS NOT NULL");
        }
        for (String table : order) {
            jdbc.update("DELETE FROM " + quoted(table));
        }
        return order;
    }

    /**
     * 지우기 전에 비워야 하는 칸: 비울 수 있는 외래 키 중 가리키는 표보다 가리켜지는 표를 먼저(또는 같은 표를) 지우는 것.
     * 순서대로 지우면 풀리는 칸은 비우지 않는다.
     */
    static List<ForeignKey> columnsToClear(List<String> order, List<ForeignKey> foreignKeys) {
        return foreignKeys.stream()
                .filter(ForeignKey::nullable)
                .filter(fk -> order.contains(fk.table()) && order.contains(fk.referencedTable()))
                .filter(fk -> order.indexOf(fk.referencedTable()) <= order.indexOf(fk.table()))
                .toList();
    }

    /**
     * 지울 순서: 아직 남은 표가 가리키지 않는 표부터(모든 외래 키, 자기 참조 제외). 같은 단계 안에서는 이름순(재현 가능).
     * 순환으로 막히면 그 단계만 비울 수 없는 외래 키로 다시 고른다(비울 수 있는 칸은 {@link #columnsToClear}로 비움).
     * 비울 수 없는 외래 키끼리 순환하면 지우지 않고 실패한다.
     */
    static List<String> deletionOrder(List<String> tables, List<ForeignKey> foreignKeys) {
        Set<String> remaining = new TreeSet<>();
        for (String table : tables) {
            if (!KEEP.contains(table)) {
                remaining.add(table);
            }
        }
        Map<String, Set<String>> referencedBy = new HashMap<>();
        Map<String, Set<String>> referencedByRequired = new HashMap<>();
        for (ForeignKey fk : foreignKeys) {
            if (!remaining.contains(fk.table()) || !remaining.contains(fk.referencedTable())) {
                continue;
            }
            if (fk.table().equals(fk.referencedTable())) {
                if (!fk.nullable()) {
                    throw new IllegalStateException("비울 수 없는 자기 참조 칸이 있어 지울 수 없습니다: "
                            + fk.table() + "." + fk.column());
                }
                continue;
            }
            referencedBy.computeIfAbsent(fk.referencedTable(), k -> new HashSet<>()).add(fk.table());
            if (!fk.nullable()) {
                referencedByRequired.computeIfAbsent(fk.referencedTable(), k -> new HashSet<>()).add(fk.table());
            }
        }

        List<String> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<String> round = unreferenced(remaining, referencedBy);
            if (round.isEmpty()) {
                round = unreferenced(remaining, referencedByRequired);
            }
            if (round.isEmpty()) {
                throw new IllegalStateException("비울 수 없는 외래 키끼리 순환이 있어 지울 순서를 정할 수 없습니다: " + remaining);
            }
            order.addAll(round);
            round.forEach(remaining::remove);
        }
        return order;
    }

    /** 남은 표 중 남은 표가 가리키지 않는 표(이름순). */
    private static List<String> unreferenced(Set<String> remaining, Map<String, Set<String>> referencedBy) {
        List<String> round = new ArrayList<>();
        for (String table : remaining) {
            if (referencedBy.getOrDefault(table, Set.of()).stream().noneMatch(remaining::contains)) {
                round.add(table);
            }
        }
        return round;
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
