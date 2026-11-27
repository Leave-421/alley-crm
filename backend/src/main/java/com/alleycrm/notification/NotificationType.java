package com.alleycrm.notification;

/** 알림 종류 · 받는 쪽 값. CONTRACTS.md 「3」 `notification.type` · `notification.recipientType`과 같아야 한다. */
public final class NotificationType {

    public static final String RESERVATION_REQUESTED = "reservation_requested";
    public static final String RESERVATION_CONFIRMED = "reservation_confirmed";
    public static final String RESERVATION_REJECTED = "reservation_rejected";
    public static final String RESERVATION_CANCELED = "reservation_canceled";
    public static final String CHANGE_REQUESTED = "change_requested";
    public static final String CHANGE_APPROVED = "change_approved";
    public static final String CHANGE_REJECTED = "change_rejected";
    public static final String REMINDER = "reminder";
    public static final String DELAY_REPORTED = "delay_reported";
    public static final String NEXT_STORE_RECOMMENDED = "next_store_recommended";
    public static final String COUPON_STATUS = "coupon_status";
    public static final String STORE_JOIN_RESULT = "store_join_result";
    public static final String VISIT_AMOUNT_RECORDED = "visit_amount_recorded";
    public static final String ASSOCIATION_COUPON_OPENED = "association_coupon_opened";
    public static final String VISIT_REPORT_CHECK_REQUESTED = "visit_report_check_requested";

    static final String RECIPIENT_ACCOUNT = "account";
    static final String RECIPIENT_GUEST_RESERVATION = "guest_reservation";

    private NotificationType() {
    }
}
