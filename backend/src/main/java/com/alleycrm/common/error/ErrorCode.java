package com.alleycrm.common.error;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 목록. 계약 문서 docs/CONTRACTS.md 「4. 에러 코드」와 같아야 한다.
 * 새 코드를 더하는 것은 계약 변경이다(계약 문서와 같은 PR에서 바꾼다).
 */
public enum ErrorCode {

    INVALID(HttpStatus.BAD_REQUEST, "invalid", "입력값을 확인해 주세요."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "unauthorized", "로그인이 필요합니다."),
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "forbidden_role", "이 기능을 쓸 수 있는 역할이 아닙니다."),
    CSRF_INVALID(HttpStatus.FORBIDDEN, "csrf_invalid", "요청 보안 토큰이 없거나 맞지 않습니다."),
    NOT_FOUND(HttpStatus.NOT_FOUND, "not_found", "찾을 수 없습니다."),
    CONFLICT(HttpStatus.CONFLICT, "conflict", "현재 상태와 맞지 않는 요청입니다."),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "서버에서 문제가 생겼습니다.");

    private final HttpStatus status;
    private final String code;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String code, String defaultMessage) {
        this.status = status;
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
