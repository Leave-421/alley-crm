package com.alleycrm.wallet;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 가상 잔액 장부(D9, FR-STL-06 · NFR-02). 실제 결제를 부르지 않고, 잔액이 바뀔 때마다 거래 내역을 한 줄 남긴다.
 * 예약금(D10) · 환불(D6) · 수수료(D15)는 이 클래스만 써서 잔액을 바꾼다. wallet 테이블을 직접 고치지 않는다.
 *
 * <p>잔액 변경은 조건부 UPDATE 한 번이라 동시에 들어와도 회원 잔액이 마이너스가 되지 않는다.
 * 부르는 쪽 트랜잭션 안에서 돈다(없으면 새로 연다).
 */
@Service
@Transactional(propagation = Propagation.REQUIRED)
public class WalletService {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public WalletService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * 잔액을 amount만큼 바꾸고(들어오면 +, 나가면 -) 거래 내역을 남긴다. 지갑이 없으면 만든다.
     *
     * @return 바뀐 뒤 잔액
     * @throws ApiException 회원 잔액이 모자라면 {@code insufficient_balance}
     */
    public long post(WalletOwner owner, String txType, long amount, Long reservationId) {
        return post(owner, txType, amount, reservationId, null);
    }

    /** 상인회 쿠폰 행사 예산 거래(D20)처럼 행사 id를 같이 남길 때 */
    public long post(WalletOwner owner, String txType, long amount, Long reservationId, Long associationEventId) {
        if (amount == 0) {
            throw new IllegalArgumentException("0원 거래는 남기지 않는다");
        }
        long walletId = ensureWallet(owner);
        int changed = jdbc.update("""
                UPDATE wallet SET balance = balance + ?
                WHERE id = ? AND (? OR balance + ? >= 0)
                """, amount, walletId, owner.mayGoNegative(), amount);
        if (changed == 0) {
            throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE);
        }
        jdbc.update("""
                INSERT INTO wallet_tx (wallet_id, type, amount, reservation_id, association_event_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, walletId, txType, amount, reservationId, associationEventId, now());
        return balance(owner);
    }

    /**
     * 한 지갑에서 다른 지갑으로 옮긴다(예: 가게 → 상인회 기금). 거래 내역은 양쪽에 한 줄씩.
     */
    public void transfer(WalletOwner from, String fromType, WalletOwner to, String toType, long amount, Long reservationId) {
        if (amount <= 0) {
            throw new IllegalArgumentException("옮기는 금액은 0보다 커야 한다");
        }
        post(from, fromType, -amount, reservationId);
        post(to, toType, amount, reservationId);
    }

    /** 비회원은 지갑이 없다. 전화번호에 묶은 가상 납부 · 환불 기록만 남긴다(FR-RSV-08). */
    public void recordGuest(String phone, String txType, long amount, long reservationId) {
        jdbc.update("""
                INSERT INTO wallet_tx (guest_phone, type, amount, reservation_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """, phone, txType, amount, reservationId, now());
    }

    /** 지갑이 없으면 0 */
    @Transactional(readOnly = true)
    public long balance(WalletOwner owner) {
        List<Long> rows = jdbc.queryForList("SELECT balance FROM wallet WHERE owner_type = ? AND owner_id = ?",
                Long.class, owner.type(), owner.id());
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    /** 최근 것부터 */
    @Transactional(readOnly = true)
    public List<WalletTx> transactions(WalletOwner owner, int page, int size) {
        return jdbc.query("""
                SELECT t.id, t.type, t.amount, t.reservation_id, t.created_at
                FROM wallet_tx t JOIN wallet w ON w.id = t.wallet_id
                WHERE w.owner_type = ? AND w.owner_id = ?
                ORDER BY t.created_at DESC, t.id DESC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new WalletTx(
                rs.getLong("id"),
                rs.getString("type"),
                rs.getLong("amount"),
                (Long) rs.getObject("reservation_id", Long.class),
                rs.getObject("created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)),
                owner.type(), owner.id(), size, (long) page * size);
    }

    @Transactional(readOnly = true)
    public long countTransactions(WalletOwner owner) {
        Long n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM wallet_tx t JOIN wallet w ON w.id = t.wallet_id
                WHERE w.owner_type = ? AND w.owner_id = ?
                """, Long.class, owner.type(), owner.id());
        return n == null ? 0 : n;
    }

    private long ensureWallet(WalletOwner owner) {
        // 동시에 처음 만들어도 uk_wallet_owner가 하나만 남긴다
        jdbc.update("INSERT IGNORE INTO wallet (owner_type, owner_id) VALUES (?, ?)", owner.type(), owner.id());
        return jdbc.queryForObject("SELECT id FROM wallet WHERE owner_type = ? AND owner_id = ?",
                Long.class, owner.type(), owner.id());
    }

    /** DB 시각은 UTC 벽시계. 시연 시계(D18)를 따르도록 서버 시계로 넣는다 */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(Instant.now(clock), ZoneOffset.UTC);
    }

    public record WalletTx(long id, String type, long amount, Long reservationId, Instant createdAt) {
    }
}
