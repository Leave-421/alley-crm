package com.alleycrm.common.scheduler;

import com.alleycrm.coupon.service.CouponExpiryService;
import com.alleycrm.reservation.ReservationStateService;
import com.alleycrm.wallet.FundBudgetService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * D7이 거는 자동 작업들(NFR-06). 비회원 정보 파기(C12)는 C가 자기 패키지에 {@link ScheduledJob} 빈을 더한다.
 */
@Configuration
class CoreScheduledJobs {

    @Bean
    ScheduledJob autoRejectJob(ReservationStateService states) {
        return job("auto_reject", states::autoRejectDue);       // FR-RSV-03 #3
    }

    @Bean
    ScheduledJob autoCancelJob(ReservationStateService states) {
        return job("auto_cancel", states::autoCancelDue);       // FR-RSV-02 #1
    }

    @Bean
    ScheduledJob couponExpiryJob(CouponExpiryService coupons) {
        return job("coupon_expiry", coupons::expireDue);        // FR-CPN-05 (E14)
    }

    @Bean
    ScheduledJob fundBudgetReleaseJob(FundBudgetService budgets) {
        return job("fund_budget_release", budgets::releaseDue); // 11-25 (5) (D20)
    }

    private static ScheduledJob job(String name, java.util.function.IntSupplier body) {
        return new ScheduledJob() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int run() {
                return body.getAsInt();
            }
        };
    }
}
