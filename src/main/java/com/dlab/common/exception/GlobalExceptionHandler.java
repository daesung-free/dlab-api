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
     * <p>응답 본문에 <b>원문 메시지는 싣지 않는다</b> — Jackson 메시지에 엔티티
     * 패키지 경로가 그대로 들어 있다. 대신 <b>필드 경로만 뽑아서</b> 싣는다.
     * "요청 본문 형식이 올바르지 않습니다"만 나가면 화면은 어느 필드가 문제인지
     * 알 수 없어, 스펙대로 보냈는데 막혔을 때 원인을 찾는 데 시간이 걸린다.
     */
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(
            org.springframework.http.converter.HttpMessageNotReadableException e) {
        log.warn("요청 본문을 읽을 수 없음: {}", e.getMessage());

        StringBuilder message = new StringBuilder("요청 본문 형식이 올바르지 않습니다");
        var mapping = mappingCauseOf(e);
        String field = fieldPathOf(mapping);
        if (field != null) {
            message.append(" — '").append(field).append('\'');
        }
        String allowed = allowedValuesOf(mapping);
        if (allowed != null) {
            message.append(". 허용값: ").append(allowed);
        }
        message.append('.');

        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message.toString()));
    }

    /**
     * 원인 사슬에서 Jackson 예외를 찾는다. Spring이 한 겹 감싸므로 직접 원인만 봐서는 놓친다.
     *
     * <p>⚠️ <b>{@code tools.jackson}(Jackson 3)이다</b> — {@code com.fasterxml.jackson}
     * (Jackson 2)도 클래스패스에 있어 그쪽으로 잡으면 <b>컴파일은 통과하는데 런타임에
     * 항상 못 찾는다.</b> 실제로 그렇게 짰다가 필드명이 안 붙었다. 필드 접근자 이름도
     * 다르다({@code getFieldName()} → {@code getPropertyName()}).
     */
    private static tools.jackson.databind.DatabindException mappingCauseOf(Throwable e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            if (t instanceof tools.jackson.databind.DatabindException jme) {
                return jme;
            }
        }
        return null;
    }

    /**
     * Jackson 예외에서 <b>필드 경로만</b> 뽑는다 — {@code applyFrom}, {@code items[2].amount}.
     *
     * <p>원문 메시지는 못 싣지만(내부 타입·패키지 경로가 그대로 들어 있다) 필드 이름은
     * 우리가 정한 API 계약이라 실어도 된다. 이게 없으면 화면은 <b>어느 필드가 문제인지
     * 모른 채</b> 본문 전체를 놓고 원인을 찾아야 한다.
     */
    private static String fieldPathOf(tools.jackson.databind.DatabindException jme) {
        if (jme == null || jme.getPath().isEmpty()) {
            return null;
        }
        StringBuilder path = new StringBuilder();
        for (var ref : jme.getPath()) {
            if (ref.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(ref.getPropertyName());
            } else if (ref.getIndex() >= 0) {
                path.append('[').append(ref.getIndex()).append(']');
            }
        }
        return path.isEmpty() ? null : path.toString();
    }

    /** enum에 없는 값이면 허용값 목록을 붙인다 — 타입 불일치 처리와 같은 이유다. */
    private static String allowedValuesOf(tools.jackson.databind.DatabindException jme) {
        if (!(jme instanceof tools.jackson.databind.exc.InvalidFormatException ife)) {
            return null;
        }
        Class<?> target = ife.getTargetType();
        if (target == null || !target.isEnum()) {
            return null;
        }
        return Arrays.stream(target.getEnumConstants())
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
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

    /**
     * 필수 파라미터·경로변수·헤더 누락.
     *
     * <p><b>처리하지 않으면 catch-all이 잡아 500으로 나간다.</b> 실제로
     * {@code /learning-plans/options}를 {@code year} 없이 부르면 500이었다 —
     * 값 하나를 빠뜨린 클라이언트 잘못인데 "서버 오류"가 돌아오면 원인을 못 찾는다.
     *
     * <p>{@link org.springframework.web.bind.ServletRequestBindingException}으로 한 번에 받는다.
     * 누락은 파라미터·경로변수·헤더·쿠키·행렬변수로 갈리는데 <b>전부 이 타입 아래</b>라,
     * 하나씩 잡으면 새 종류가 생길 때마다 500이 다시 샌다.
     *
     * <p>어느 값이 빠졌는지는 <b>{@code MissingRequestValueException}일 때만</b> 싣는다 —
     * 그 아래에만 이름이 있고, 상위 타입은 메시지에 내부 정보가 섞일 수 있다.
     */
    @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingValue(
            org.springframework.web.bind.ServletRequestBindingException e) {
        log.warn("요청 값 바인딩 실패: {}", e.getMessage());

        String message = "필수 요청 값이 없습니다";
        if (e instanceof org.springframework.web.bind.MissingRequestValueException missing) {
            String name = nameOf(missing);
            if (name != null) {
                message = "필수 요청 값이 없습니다: '" + name + "'";
            }
        }
        return ResponseEntity.status(ErrorCode.INVALID_REQUEST.getStatus())
                .body(ApiResponse.fail(ErrorCode.INVALID_REQUEST, message + "."));
    }

    /** 누락 종류마다 이름을 담는 자리가 달라서 갈라 본다. */
    private static String nameOf(org.springframework.web.bind.MissingRequestValueException e) {
        if (e instanceof org.springframework.web.bind.MissingServletRequestParameterException p) {
            return p.getParameterName();
        }
        if (e instanceof org.springframework.web.bind.MissingPathVariableException v) {
            return v.getVariableName();
        }
        if (e instanceof org.springframework.web.bind.MissingRequestHeaderException h) {
            return h.getHeaderName();
        }
        return null;
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
