package com.dlab.api.admin.staff;

import com.dlab.domain.user.entity.Employee;
import com.dlab.domain.user.entity.Teacher;
import java.util.Set;

/**
 * 선생님·직원 응답. 어느 쪽인지 {@code kind}로 구분한다.
 *
 * <p><b>계정 정보를 함께 내린다.</b> 사용자 관리(F-4.10-2)는 역할을 보고 바꾸는 화면인데,
 * {@code accountId}가 없으면 역할 변경 API를 <b>부를 수조차 없고</b> 지금 역할이 뭔지도
 * 알 수 없었다.
 *
 * @param accountId 계정이 아직 없는 직원은 비어 있다 — 등록만 하고 계정은 나중에 주는
 *                  경우가 있다. 화면은 이 값으로 "계정 발급" 버튼을 갈라야 한다
 * @param roles     {@code SUPER_ADMIN}·{@code BRANCH_ADMIN}·{@code TEACHER}·
 *                  {@code STAFF}·{@code READONLY}. 계정이 없으면 빈 집합이다
 * @param locked    로그인 실패 5회로 잠긴 상태. 관리자가 풀어줘야 한다
 */
public record StaffResponse(
        String kind,
        Long id,
        Long academyId,
        String name,
        String deptName,
        String positionName,
        String phone,
        String email,
        Long accountId,
        String loginId,
        Set<String> roles,
        boolean locked,
        boolean mustChangePassword
) {

    /** 담당선생님(사감) — 반 담임·승인 에스컬레이션 대상이 될 수 있다. */
    public static StaffResponse from(Teacher t) {
        return of(t, null, Set.of());
    }

    public static StaffResponse of(Teacher t, com.dlab.domain.user.entity.Account account,
                                   Set<String> roles) {
        return new StaffResponse("TEACHER", t.getId(), t.getAcademy().getId(),
                t.getName(), null, null, t.getPhone(), t.getEmail(),
                account == null ? null : account.getId(),
                account == null ? null : account.getLoginId(),
                roles,
                account != null && account.isLocked(),
                account != null && account.isMustChangePassword());
    }

    /** 행정 — 행정선생님 포함. 학생 가입 승인 주체다. */
    public static StaffResponse from(Employee e) {
        return of(e, null, Set.of());
    }

    public static StaffResponse of(Employee e, com.dlab.domain.user.entity.Account account,
                                   Set<String> roles) {
        return new StaffResponse("EMPLOYEE", e.getId(), e.getAcademy().getId(),
                e.getName(), e.getDeptName(), e.getPositionName(), e.getPhone(), e.getEmail(),
                account == null ? null : account.getId(),
                account == null ? null : account.getLoginId(),
                roles,
                account != null && account.isLocked(),
                account != null && account.isMustChangePassword());
    }
}
