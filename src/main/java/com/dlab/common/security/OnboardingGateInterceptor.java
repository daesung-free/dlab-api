package com.dlab.common.security;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.user.entity.OnboardingStatus;
import com.dlab.domain.user.repository.AccountRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 대면 OT 전에는 「신청」을 막는다 (앱 요구사항 A-2 · I-24).
 *
 * <h2>★ 전면 차단이 아니다</h2>
 * OT 전에도 <b>조회는 열어 둔다</b> — 공지·일정·급식표를 못 보면 앱을 깐 의미가 없고,
 * 학원에 와서 OT 를 받기까지 며칠이 걸린다. 막는 것은 <b>학원이 처리해야 하는 「신청」</b>뿐이다.
 *
 * <h2>★★ 온보딩을 끝내는 데 필요한 것은 막지 않는다</h2>
 * ⚠️ <b>정기일정 등록을 막으면 온보딩이 영원히 끝나지 않는다</b> — {@code SCHEDULE_SET} 이
 * 온보딩 단계라, 막으면 학생이 그 단계를 통과할 방법이 없어진다.
 * 학부모 연동도 <b>학부모가</b> 하는 일이라 여기 걸리지 않는다.
 *
 * <h2>★★ 기본은 꺼져 있다 (I-24 미확정)</h2>
 * 「OT 게이트 범위」가 안 정해졌다. 범위를 정해 켜 두었다가 <b>기존 동작을 10곳에서
 * 깨뜨렸다</b> — 방화벽 해제 테스트가 「온보딩과 무관하게 된다」를 전제하고 있었다.
 * 코드베이스의 기존 전제와 어긋난다는 신호였다.
 *
 * <p>그래서 <b>장치만 만들어 두고 끈다</b>(§6 원칙 3 — 블로커는 임의로 가정하지 않는다).
 * 범위가 확정되면 {@code onboarding.ot-gate.enabled=true} 로 켠다.
 *
 * <h2>★ 목록을 한 곳에 둔다</h2>
 * 서비스마다 검사를 흩뿌리면 범위가 바뀔 때 전부 찾아야 하므로 <b>경로 목록 한 곳</b>으로
 * 둔다 — 답이 오면 이 파일만 고친다.
 *
 * <h2>앱이 알아서 라우팅하게 두지 않는 이유</h2>
 * 클라이언트만 믿으면 앱을 우회한 직접 호출로 다 쓸 수 있다
 * ({@link PasswordChangeRequiredInterceptor} 와 같은 판단).
 */
@Component
@RequiredArgsConstructor
public class OnboardingGateInterceptor implements HandlerInterceptor {

    /**
     * OT 완료 전에 막는 경로.
     *
     * <p>★ <b>「학원이 처리해야 하는 신청」만</b> 넣는다. 조회는 넣지 않는다.
     *
     * <p>⚠️ 여기 <b>넣지 말아야 하는 것</b>:
     * <ul>
     *   <li>{@code /app/regular-schedules/**} — 온보딩 단계({@code SCHEDULE_SET})다.
     *       막으면 온보딩이 끝나지 않는다</li>
     *   <li>{@code /app/auth/**} · {@code /app/me} · {@code /app/app-config} —
     *       막으면 앱이 아예 뜨지 않는다</li>
     *   <li>{@code /app/terms/**} — 동의를 받아야 쓸 수 있는데 막으면 순서가 꼬인다</li>
     * </ul>
     */
    private static final List<String> BLOCKED_BEFORE_OT = List.of(
            "/api/v1/app/meals/**",            // 급식 신청·취소
            "/api/v1/app/firewall/**",         // 와이파이 해제 신청
            "/api/v1/app/lectures/*/apply",    // 특강 신청
            "/api/v1/app/qna/**",              // 질의응답 작성
            "/api/v1/app/consults/**",         // 상담 예약
            "/api/v1/app/absence-requests/**"  // 사유 제출
    );

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final AccountRepository accountRepository;

    /** ★ 기본 꺼짐. I-24(게이트 범위)가 확정되면 켠다. */
    @org.springframework.beans.factory.annotation.Value("${onboarding.ot-gate.enabled:false}")
    private boolean enabled;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!enabled) {
            return true;
        }
        // 프리플라이트는 인증 헤더를 싣지 않는다
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // ★ 조회는 열어 둔다 — 급식표·공지를 못 보면 앱을 깐 의미가 없다
        if (HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        if (!isBlocked(request.getRequestURI())) {
            return true;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof AuthPrincipal principal)) {
            return true;
        }

        // ★ 학생만 대상이다. 학부모·직원에게는 온보딩이 없다
        accountRepository.findById(principal.accountId())
                .map(a -> a.getStudent())
                .filter(student -> student != null
                        && student.getOnboardingStatus() == OnboardingStatus.REGISTERED)
                .ifPresent(student -> {
                    throw new BusinessException(ErrorCode.OT_REQUIRED);
                });
        return true;
    }

    private boolean isBlocked(String uri) {
        return BLOCKED_BEFORE_OT.stream().anyMatch(p -> MATCHER.match(p, uri));
    }
}
