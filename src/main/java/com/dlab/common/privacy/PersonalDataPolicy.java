package com.dlab.common.privacy;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;

/**
 * 개인정보 원본 열람 가능 여부 (실행가이드 3.2 — "전화·주소·생년월일이 포함된 응답은 상위 관리자만").
 *
 * <p><b>"상위 관리자"를 {@code SUPER_ADMIN} + {@code BRANCH_ADMIN}으로 해석했다.</b>
 * 지침이 5단계 RBAC 중 어디까지인지를 명시하지 않았는데, 지점 관리자는 그 지점 학생의
 * 보호자에게 직접 연락하는 것이 업무라 제외하면 화면이 돌아가지 않는다. 반면
 * {@code TEACHER}·{@code STAFF}·{@code READONLY}는 마스킹된 값으로 업무가 성립한다.
 *
 * <p>권한 매트릭스({@code permission} 테이블)를 수령하면 이 판정을 데이터로 옮긴다 —
 * 그때까지만 코드에 둔다.
 */
public final class PersonalDataPolicy {

    private PersonalDataPolicy() {
    }

    /** 전화·생년월일·주소 원본을 볼 수 있는가. */
    public static boolean canViewRaw(AuthPrincipal principal) {
        return principal != null
                && (principal.hasRole(Role.SUPER_ADMIN) || principal.hasRole(Role.BRANCH_ADMIN));
    }

    /** 권한이 없으면 마스킹해서 돌려준다. */
    public static String phone(AuthPrincipal principal, String phone) {
        return canViewRaw(principal) ? phone : Masking.phone(phone);
    }
}
