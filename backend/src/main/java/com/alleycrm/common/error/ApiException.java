package com.alleycrm.common.error;

/**
 * 서비스 계층에서 던지는 예외. 전역 처리기가 계약의 에러 응답 모양으로 바꾼다.
 * 예: {@code throw new ApiException(ErrorCode.NOT_FOUND);}
 */
public class ApiException extends RuntimeException {

    private final ErrorCode errorCode;

    public ApiException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage());
    }

    public ApiException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
