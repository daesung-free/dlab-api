package com.dlab.domain.menu.service;

import com.dlab.common.security.Role;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 역할별 기본 메뉴 (권한 매트릭스 초안, {@code docs/permission-matrix.md}).
 *
 * <h2>왜 필요한가</h2>
 * 계정별 노출 설정은 최고관리자가 <b>계정 하나하나에</b> 고르는 것이고(0914 확정), 고르지 않은
 * 계정에는 카탈로그 전체가 내려간다. 그래서 담임 사이드바에 급식·수납처럼 <b>애초에 열려 있지
 * 않은 메뉴가 그대로 보였다</b> — 눌러 봐야 안 된다는 것을 알게 된다.
 *
 * <p>여기서 <b>역할 기본값</b>을 준다. 계정별 설정이 있으면 그쪽이 이기므로 0914 확정과
 * 충돌하지 않는다 — 기본값은 아무도 고르지 않았을 때만 쓰인다.
 *
 * <h2>★ 이것은 권한이 아니다</h2>
 * 메뉴를 빼도 권한이 줄지 않고, 넣어도 늘지 않는다. 주소로 직접 들어가는 것은 결국
 * {@code @PreAuthorize}가 막는다. 여기는 <b>화면에 무엇을 보여줄지</b>일 뿐이다 —
 * 반대로 이해하면 메뉴 한 칸이 권한 상승이 된다.
 *
 * <h2>표와 어긋난 두 곳은 API 를 따랐다</h2>
 * <ul>
 *   <li><b>급식 — 조회 전용</b>: 표는 「없음」이지만 조회는 열려 있다(「보기만」이 그 권한의 뜻이다).
 *       API 가 되는데 메뉴만 감추면 화면에서 갈 길이 없는 기능이 생긴다</li>
 *   <li><b>공지 — 담임</b>: 표는 「없음」인데 확정사항은 <b>반공지는 담당선생님만 작성</b>이다
 *       (CLAUDE.md §2). 확정사항을 따른다</li>
 * </ul>
 * 매트릭스가 확정되면 이 표를 그쪽에 맞추고, 값은 데이터로 옮긴다.
 */
public final class RoleMenuDefaults {

    private static final Set<Role> ALL = EnumSet.allOf(Role.class);
    private static final Set<Role> MANAGERS = EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN);
    private static final Set<Role> HEAD_OFFICE = EnumSet.of(Role.SUPER_ADMIN);

    private static final Set<Role> WITH_TEACHER =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.TEACHER);
    private static final Set<Role> WITH_STAFF =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.STAFF);
    private static final Set<Role> DESK_AND_VIEWER =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.STAFF, Role.READONLY);
    private static final Set<Role> TEACHER_AND_VIEWER =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.TEACHER, Role.READONLY);

    /**
     * 메뉴 코드 → 기본으로 보이는 역할.
     *
     * <p><b>여기 없는 메뉴는 전 역할에 보인다.</b> 메뉴가 새로 생겼을 때 조용히 사라지는 것보다
     * 보이는 편이 낫다 — 안 보이면 기능이 없는 줄 안다.
     */
    private static final Map<String, Set<Role>> BY_MENU = Map.ofEntries(
            // 학생 — 담임은 맡은 학생만, 조회 전용은 보기만 (서버가 범위를 건다)
            Map.entry("student", ALL),
            Map.entry("attendance", ALL),
            Map.entry("statistics", ALL),
            Map.entry("grade", TEACHER_AND_VIEWER),

            // 담임 업무 — 행정·조회 전용에는 없다
            Map.entry("class", WITH_TEACHER),
            Map.entry("penalty", WITH_TEACHER),
            Map.entry("absence", WITH_TEACHER),
            Map.entry("approval", WITH_TEACHER),
            Map.entry("firewall", WITH_TEACHER),
            Map.entry("schedule", WITH_TEACHER),
            Map.entry("consult", WITH_TEACHER),
            Map.entry("routine", WITH_TEACHER),
            Map.entry("learning-plan", WITH_TEACHER),
            Map.entry("personal-record", WITH_TEACHER),
            Map.entry("timetable", WITH_TEACHER),
            Map.entry("survey", WITH_TEACHER),
            Map.entry("lecture", WITH_TEACHER),

            // 공지 — 전체공지는 행정, 반공지는 담임(CLAUDE.md §2)
            Map.entry("notice", EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN,
                    Role.TEACHER, Role.STAFF)),
            Map.entry("qna", EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN,
                    Role.TEACHER, Role.STAFF)),

            // 데스크 업무 — 담임에게는 없다
            Map.entry("billing", DESK_AND_VIEWER),
            Map.entry("meal", DESK_AND_VIEWER),
            Map.entry("tuition", WITH_STAFF),
            Map.entry("payment-request", WITH_STAFF),
            Map.entry("cash-receipt", WITH_STAFF),
            Map.entry("meal-vendor", WITH_STAFF),
            Map.entry("notification", WITH_STAFF),
            Map.entry("seat", WITH_STAFF),
            Map.entry("waiting", WITH_STAFF),
            Map.entry("student-signup", WITH_STAFF),   // 가입 승인은 행정이 한다
            Map.entry("app-account", WITH_STAFF),      // 잠금 해제·임시 비밀번호

            // 설정 — 본사·지점관리자
            Map.entry("master", MANAGERS),
            Map.entry("holiday", MANAGERS),
            Map.entry("period", MANAGERS),
            Map.entry("scholarship", MANAGERS),
            Map.entry("staff", MANAGERS),
            Map.entry("audit-log", MANAGERS),
            Map.entry("app-config", MANAGERS),

            // 연동 키 — 본사만. 지점이 키오스크 자격증명을 재발급하면 그 지점 단말이 전부 멈춘다
            Map.entry("branch-config", HEAD_OFFICE),
            Map.entry("pg-site", HEAD_OFFICE)
    );

    private RoleMenuDefaults() {
    }

    /** 이 역할들에 기본으로 보이는 메뉴인가. 표에 없는 메뉴는 보인다. */
    public static boolean visibleTo(String menuCode, Set<Role> roles) {
        Set<Role> allowed = BY_MENU.get(menuCode);
        if (allowed == null) {
            return true;
        }
        return roles.stream().anyMatch(allowed::contains);
    }
}
