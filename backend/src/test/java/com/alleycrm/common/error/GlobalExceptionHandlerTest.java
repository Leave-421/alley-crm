package com.alleycrm.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void ApiException은_코드의_상태와_계약_모양으로_바뀐다() {
        ResponseEntity<ErrorResponse> res = handler.handleApi(new ApiException(ErrorCode.CONFLICT, "이미 찬 시간입니다."));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody().error().code()).isEqualTo("conflict");
        assertThat(res.getBody().error().message()).isEqualTo("이미 찬 시간입니다.");
    }

    @Test
    void 예상하지_못한_예외는_500_internal이고_내부_메시지를_내보내지_않는다() {
        ResponseEntity<ErrorResponse> res = handler.handleUnexpected(new IllegalStateException("secret detail"));

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody().error().code()).isEqualTo("internal");
        assertThat(res.getBody().error().message()).doesNotContain("secret");
    }

    @Test
    void 에러_코드는_모두_소문자_snake다() {
        for (ErrorCode c : ErrorCode.values()) {
            assertThat(c.code()).matches("[a-z]+(_[a-z]+)*");
        }
    }
}
