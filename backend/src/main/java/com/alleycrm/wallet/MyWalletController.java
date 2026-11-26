package com.alleycrm.wallet;

import com.alleycrm.common.error.ApiException;
import com.alleycrm.common.error.ErrorCode;
import com.alleycrm.wallet.WalletService.WalletTx;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 가상 잔액 · 거래 내역(D9). 회원은 자기 지갑, 사장님은 자기 가게 지갑, 상인회는 권역 공동 기금.
 * 지갑 주인은 로그인 계정으로만 정하고 ID를 받지 않는다(IDOR).
 * 가상 충전(D10): 회원(FR-RSV-08 #2)과 사장님 가게(FR-STL-06 #2, 기획 11-25 (4)). 상인회 기금은 충전하지 않는다.
 */
@RestController
class MyWalletController {

    /** 한 번에 충전할 수 있는 금액(원) */
    static final int MIN_CHARGE = 1_000;
    static final int MAX_CHARGE = 1_000_000;

    private final WalletService wallets;
    private final JdbcTemplate jdbc;
    MyWalletController(WalletService wallets, JdbcTemplate jdbc) {
        this.wallets = wallets;
        this.jdbc = jdbc;
    }

    @PostMapping("/api/v1/wallets/me/charge")
    @Transactional
    WalletResponse charge(@RequestBody ChargeRequest req, Authentication auth) {
        WalletOwner owner = ownerOf(auth);
        if (WalletOwner.FUND.equals(owner.type())) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        if (req == null || req.amount() == null || req.amount() < MIN_CHARGE || req.amount() > MAX_CHARGE) {
            throw new ApiException(ErrorCode.INVALID);
        }
        long balance = wallets.post(owner, WalletTxType.CHARGE, req.amount(), null);
        return new WalletResponse(owner.type(), balance, WalletOwner.STORE.equals(owner.type()) && balance < 0);
    }

    record ChargeRequest(Integer amount) {
    }

    @GetMapping("/api/v1/wallets/me")
    WalletResponse me(Authentication auth) {
        WalletOwner owner = ownerOf(auth);
        long balance = wallets.balance(owner);
        return new WalletResponse(owner.type(), balance, WalletOwner.STORE.equals(owner.type()) && balance < 0);
    }

    @GetMapping("/api/v1/wallets/me/transactions")
    Map<String, Object> transactions(Authentication auth,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new ApiException(ErrorCode.INVALID);
        }
        WalletOwner owner = ownerOf(auth);
        List<TxResponse> items = wallets.transactions(owner, page, size).stream().map(TxResponse::of).toList();
        long total = wallets.countTransactions(owner);
        return Map.of("items", items, "page", page, "size", size,
                "totalElements", total, "totalPages", (total + size - 1) / size);
    }

    /** C2 계정(로그인 아이디 = 인증 이름)에서 지갑 주인을 찾는다 */
    private WalletOwner ownerOf(Authentication auth) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.role, a.store_id, a.region_id, c.id AS customer_id
                FROM account a LEFT JOIN customer c ON c.account_id = a.id
                WHERE a.login_id = ?
                """, auth.getName());
        if (rows.isEmpty()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        Map<String, Object> a = rows.get(0);
        String role = (String) a.get("role");
        Object id = switch (role) {
            case "customer" -> a.get("customer_id");
            case "owner" -> a.get("store_id");
            case "association" -> a.get("region_id");
            default -> null;
        };
        if (id == null) {
            throw new ApiException(ErrorCode.FORBIDDEN_ROLE);
        }
        long ownerId = ((Number) id).longValue();
        return switch (role) {
            case "customer" -> WalletOwner.customer(ownerId);
            case "owner" -> WalletOwner.store(ownerId);
            default -> WalletOwner.fund(ownerId);
        };
    }

    record WalletResponse(String ownerType, long balance, boolean needsCharge) {
    }

    record TxResponse(String id, String type, long amount, String reservationId, Instant createdAt) {
        static TxResponse of(WalletTx t) {
            return new TxResponse(String.valueOf(t.id()), t.type(), t.amount(),
                    t.reservationId() == null ? null : String.valueOf(t.reservationId()), t.createdAt());
        }
    }
}
