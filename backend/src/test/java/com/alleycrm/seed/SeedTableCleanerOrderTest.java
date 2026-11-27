package com.alleycrm.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.alleycrm.seed.SeedTableCleaner.ForeignKey;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 외래 키에서 지울 순서를 계산하는 규칙. DB 없이 돈다. */
class SeedTableCleanerOrderTest {

    @Test
    void 가리키는_표부터_지우고_순서로_풀리면_칸을_비우지_않으며_flyway_기록은_남긴다() {
        List<String> tables = List.of("account", "customer", "flyway_schema_history", "region", "setting", "store", "visitor");
        List<ForeignKey> fks = List.of(
                new ForeignKey("store", "region_id", "region", false),
                new ForeignKey("account", "store_id", "store", true),
                new ForeignKey("account", "region_id", "region", true),
                new ForeignKey("customer", "account_id", "account", true),
                new ForeignKey("visitor", "customer_id", "customer", true),
                new ForeignKey("setting", "region_id", "region", false));

        List<String> order = SeedTableCleaner.deletionOrder(tables, fks);

        assertThat(order).containsExactly("setting", "visitor", "customer", "account", "store", "region");
        assertThat(SeedTableCleaner.columnsToClear(order, fks)).isEmpty();
    }

    @Test
    void 예약과_정식_코스_제안의_순환은_비울_수_있는_칸으로_풀린다() {
        List<ForeignKey> fks = List.of(
                new ForeignKey("reservation", "store_id", "store", false),
                new ForeignKey("reservation", "course_root_id", "reservation", true),
                new ForeignKey("reservation", "course_proposal_id", "course_proposal", true),
                new ForeignKey("course_proposal", "base_reservation_id", "reservation", false),
                new ForeignKey("course_proposal", "recommended_store_id", "store", false));

        List<String> order = SeedTableCleaner.deletionOrder(List.of("course_proposal", "reservation", "store"), fks);

        assertThat(order).containsExactly("course_proposal", "reservation", "store");
        assertThat(SeedTableCleaner.columnsToClear(order, fks)).extracting(ForeignKey::column)
                .containsExactlyInAnyOrder("course_root_id", "course_proposal_id");
    }

    @Test
    void 지갑_거래는_지갑보다_먼저_지워_CHECK가_걸린_칸을_비우지_않는다() {
        // wallet_tx.wallet_id는 비울 수 있지만 ck_wallet_tx_owner(지갑 또는 비회원 전화번호) 때문에 비우면 안 된다
        List<ForeignKey> fks = List.of(
                new ForeignKey("wallet_tx", "wallet_id", "wallet", true),
                new ForeignKey("wallet_tx", "reservation_id", "reservation", true));
        List<String> order = SeedTableCleaner.deletionOrder(List.of("reservation", "wallet", "wallet_tx"), fks);

        assertThat(order.indexOf("wallet_tx")).isLessThan(order.indexOf("wallet"));
        assertThat(SeedTableCleaner.columnsToClear(order, fks)).isEmpty();
    }

    @Test
    void 비울_수_없는_자기_참조나_순환이_있으면_지우지_않고_실패() {
        List<ForeignKey> selfNotNull = List.of(new ForeignKey("node", "parent_id", "node", false));
        List<ForeignKey> cycle = List.of(
                new ForeignKey("a", "b_id", "b", false),
                new ForeignKey("b", "a_id", "a", false));

        assertThatThrownBy(() -> SeedTableCleaner.deletionOrder(List.of("node"), selfNotNull))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("node.parent_id");
        assertThatThrownBy(() -> SeedTableCleaner.deletionOrder(List.of("a", "b"), cycle))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("순환");
    }
}
