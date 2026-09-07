package com.dlab.common.exception;

import com.dlab.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Arrays;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 응답 메시지에 실을 사용자 입력값의 최대 길이. */
    private static final int VALUE_MAX_LENGTH = 50;

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("도메인 오류: {} - {}", e.getErrorCode(), e.getMessage());
        return ResponseEntity.status(e.getErrorCode().getStatus())
                .body(ApiResponse.fail(e.getErrorCode(), e.getMessage()));
    }

    /** @Valid 검증 실패 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message));
    }

    /**
     * {@code @PreAuthorize} 등 메서드 레벨 권한 거부.
     *
     * <p>SecurityConfig의 {@code accessDeniedHandler}는 필터 단계만 담당한다 —
     * 컨트롤러 진입 후 던져지는 이 예외는 여기로 온다. 처리하지 않으면 아래 catch-all이
     * 잡아 <b>권한 부족이 500으로 나간다</b>.
     */
    @ExceptionHandler(AuthorizationDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AuthorizationDeniedException e) {
        return ResponseEntity.status(ErrorCode.FORBIDDEN.getStatus())
                .body(ApiResponse.fail(ErrorCode.FORBIDDEN));
    }

    /**
     * 없는 경로 · 허용 안 된 메서드.
     *
     * <p><b>이걸 처리하지 않으면 아래 catch-all이 잡아 500으로 나간다.</b>
     * URL 오타 하나가 "서버 오류"로 보이면, 앱 개발자가 서버를 의심하며 시간을 쓴다.
     * 실제로 테스트에서 이 경로로 드러났다.
     */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiResponse<Void>> handleNotFound(Exception e) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.getStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getStatus())
                .body(ApiResponse.fail(ErrorCode.METHOD_NOT_ALLOWED, e.getMessage()));
    }

    /**
     * 본문을 읽을 수 없음 — 깨진 JSON, <b>enum 오타</b>, 타입 불일치.
     *
     * <p><b>처리하지 않으면 아래 catch-all이 잡아 500으로 나간다.</b> 실제로
     * {@code "track":"NATURAL"}(정답은 {@code SCIENCE}) 하나에 서버 오류가 났다 —
     * 앱 쪽 오타인데 응답이 500이면 서버를 의심하며 시간을 쓰게 된다.
     *
     * <p>응답 본문에는 <b>원문 메시지를 싣지 않는다</b> — Jackson 메시지에 엔티티
     * 패키지 경로가 그대로 들어 있다. 대신 <b>서버 로그에 남기므로</b> 어느 필드가
     * 문제였는지는 로그에서 확인한다.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("요청 본문을 읽을 수 없음: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, "요청 본문 형식이 올바르지 않습니다."));
    }

    /**
     * 쿼리 파라미터·경로변수의 타입 불일치 — {@code ?year=abc}, {@code ?academyId=xyz},
     * <b>enum에 없는 값</b>.
     *
     * <p><b>처리하지 않으면 아래 catch-all이 잡아 500으로 나간다.</b> 값 하나를 잘못 보낸
     * 클라이언트 잘못인데 응답이 "서버 오류"면, 앱·웹 개발자가 서버를 의심하며 시간을 쓴다.
     *
     * <p>메시지에는 <b>어느 파라미터가 문제인지</b>({@code e.getName()})와 enum이면
     * <b>허용값 목록</b>까지 담는다 — 화면이 원인을 바로 안다. 다만 사용자가 보낸 값은
     * 길 수 있어 잘라서 싣고, <b>원문 예외 메시지는 싣지 않는다</b>(내부 타입·패키지 경로가
     * 그대로 노출된다). 상세는 서버 로그에서 확인한다.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("파라미터 타입 불일치: {} = {}", e.getName(), e.getValue());

        StringBuilder message = new StringBuilder()
                .append("파라미터 '").append(e.getName()).append("' 값이 올바르지 않습니다");

        String value = abbreviate(e.getValue());
        if (value != null) {
            message.append(": ").append(value);
        }

        Class<?> required = e.getRequiredType();
        if (required != null && required.isEnum()) {
            message.append(" (허용값: ")
                    .append(Arrays.stream(required.getEnumConstants())
                            .map(String::valueOf)
                            .collect(Collectors.joining(", ")))
                    .append(")");
        }

        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message.toString()));
    }

    /**
     * 필수 쿼리 파라미터 누락 — {@code /masters/course-types}를 {@code academyId} 없이 부른 경우.
     *
     * <p>바로 위 핸들러가 <b>"값이 틀린" 경우</b>만 잡고 <b>"값이 아예 없는" 경우</b>는
     * 안 잡아서, 그동안 catch-all로 떨어져 <b>500 INTERNAL_ERROR</b>가 나갔다.
     * {@code ?year=abc}는 400인데 {@code ?}를 통째로 빼면 500이 되는, 설명하기 어려운 상태였다.
     *
     * <p>클라이언트가 뭘 빠뜨렸는지 알려주는 게 목적이라 <b>파라미터 이름을 싣는다</b>.
     * 이름만으로는 원인을 못 찾아 화면 개발자가 서버를 의심하며 시간을 쓴다.
     *
     * <p>{@code MissingServletRequestPartException}(멀티파트 누락)도 같이 받는다 —
     * 성적표·합격증 업로드가 붙으면 같은 유형이 생긴다.
     */
    @ExceptionHandler({org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(Exception e) {
        String name = e instanceof org.springframework.web.bind.MissingServletRequestParameterException missing
                ? missing.getParameterName()
                : ((org.springframework.web.multipart.support.MissingServletRequestPartException) e)
                        .getRequestPartName();

        log.warn("필수 파라미터 누락: {}", name);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST,
                        "필수 파라미터 '%s' 이(가) 없습니다.".formatted(name)));
    }

    /**
     * {@code @RequestParam}에 붙은 제약 위반 — {@code @Min}·{@code @Size} 등.
     *
     * <p>본문({@code @RequestBody})은 {@link MethodArgumentNotValidException}이 잡지만
     * <b>파라미터 쪽은 다른 예외</b>라 역시 catch-all로 새어 500이 된다.
     */
    @ExceptionHandler(org.springframework.web.method.annotation.HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleParameterValidation(
            org.springframework.web.method.annotation.HandlerMethodValidationException e) {

        String message = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream())
                .map(org.springframework.context.MessageSourceResolvable::getDefaultMessage)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.joining(", "));

        log.warn("파라미터 검증 실패: {}", message);
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST,
                        message.isEmpty() ? "파라미터 값이 올바르지 않습니다." : message));
    }

    /**
     * 날짜·시각 형식 오류 — <b>바인딩을 통과한 뒤 본문에서 파싱하다 터진 경우</b>.
     *
     * <p>파라미터를 {@code String}으로 받아 메서드 안에서 {@code YearMonth.parse()} 같은 것을
     * 부르면 위 핸들러에 <b>안 걸린다</b> — 바인딩은 성공했기 때문이다. 그대로 두면 catch-all이
     * 잡아 <b>500</b>으로 나간다. 실제로 {@code /admin/meals/monthly?month=9}가 그랬다.
     *
     * <p><b>근본 해법은 파라미터 타입을 {@code YearMonth}로 직접 받는 것</b>이고 그렇게 고쳤다.
     * 이 핸들러는 <b>같은 실수가 또 나와도 500으로는 새어나가지 않게</b> 하는 안전망이다.
     *
     * <p>원문 메시지({@code "Text '9' could not be parsed at index 0"})는 싣지 않는다 —
     * 내부 구현이 드러나고 화면에 그대로 노출하기도 어렵다.
     */
    @ExceptionHandler(java.time.format.DateTimeParseException.class)
    public ResponseEntity<ApiResponse<Void>> handleDateParse(java.time.format.DateTimeParseException e) {
        log.warn("날짜 형식 오류: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST,
                        "날짜 형식이 올바르지 않습니다: " + abbreviate(e.getParsedString())));
    }

    /** 사용자가 보낸 값을 메시지에 실을 수 있는 길이로 자른다. */
    private static String abbreviate(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.length() <= VALUE_MAX_LENGTH ? text : text.substring(0, VALUE_MAX_LENGTH) + "...";
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception e) {
        log.error("처리되지 않은 예외", e);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.getStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }
}
