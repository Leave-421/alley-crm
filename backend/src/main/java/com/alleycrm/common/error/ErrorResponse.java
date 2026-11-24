package com.alleycrm.common.error;

/** 에러 응답 본문: {@code { "error": { "code": "...", "message": "..." } }} */
public record ErrorResponse(Body error) {

    public record Body(String code, String message) {
    }

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return new ErrorResponse(new Body(errorCode.code(), message));
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.defaultMessage());
    }

    /** 보안 필터처럼 MVC 밖에서 쓸 JSON 문자열. 메시지는 고정 문구라 따로 이스케이프하지 않는다. */
    public static String json(ErrorCode errorCode) {
        return "{\"error\":{\"code\":\"" + errorCode.code() + "\",\"message\":\"" + errorCode.defaultMessage() + "\"}}";
    }
}
