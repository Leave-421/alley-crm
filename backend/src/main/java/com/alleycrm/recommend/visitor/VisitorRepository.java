package com.alleycrm.recommend.visitor;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 표 visitor(D1). 손님 잇기는 조건부 UPDATE 한 번으로 해서, 동시에 와도 먼저 온 쪽만 잇는다.
 * 시각은 UTC 값이다.
 */
@Repository
class VisitorRepository {

    record Visitor(long id, Long customerId) {
    }

    private final JdbcTemplate jdbc;

    VisitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<Visitor> findByAnonId(UUID anonId) {
        List<Visitor> rows = jdbc.query(
                "SELECT id, customer_id FROM visitor WHERE anon_id = ?",
                (rs, n) -> new Visitor(rs.getLong("id"), rs.getObject("customer_id", Long.class)),
                anonId.toString());
        return rows.stream().findFirst();
    }

    /** 새 방문자. 손님을 바로 이을 때는 customerId · linkedAt을 함께 넣는다. */
    long insert(UUID anonId, Long customerId, LocalDateTime linkedAt) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO visitor (anon_id, customer_id, linked_at) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, anonId.toString());
            ps.setObject(2, customerId);
            ps.setObject(3, linkedAt);
            return ps;
        }, keys);
        return keys.getKey().longValue();
    }

    /** 아직 손님이 없을 때만 잇는다. 바뀐 행 수(0 또는 1). */
    int linkIfUnlinked(long visitorId, long customerId, LocalDateTime now) {
        return jdbc.update(
                "UPDATE visitor SET customer_id = ?, linked_at = ? WHERE id = ? AND customer_id IS NULL",
                customerId, now, visitorId);
    }
}
