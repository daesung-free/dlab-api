package com.dlab.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

/**
 * 컨트롤러 파라미터에 현재 인증 주체를 주입한다.
 *
 * <pre>{@code
 * @GetMapping("/students")
 * public ApiResponse<...> search(@CurrentAccount AuthPrincipal me, ...) {
 *     Long scope = me.academyScopeFilter();   // 지점 필터 강제
 * }
 * }</pre>
 *
 * <p>{@code SecurityContextHolder}를 서비스에서 직접 뒤지지 말 것 —
 * 테스트가 어려워지고 배치·스케줄러처럼 인증 주체가 없는 경로에서 조용히 NPE가 난다.
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@AuthenticationPrincipal
public @interface CurrentAccount {
}
