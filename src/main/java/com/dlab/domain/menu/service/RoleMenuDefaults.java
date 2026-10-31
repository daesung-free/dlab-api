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
@org.springframework.stereotype.Component
@lombok.RequiredArgsConstructor
public class RoleMenuDefaults {

    private final com.dlab.domain.menu.repository.RoleMenuDefaultRepository repository;


    private static final Set<Role> ALL = EnumSet.allOf(Role.class);
    private static final Set<Role> MANAGERS = EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN);
    private static final Set<Role> HEAD_OFFICE = EnumSet.of(Role.SUPER_ADMIN);

    private static final Set<Role> WITH_TEACHER =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.TEACHER);
    private static final Set<Role> WITH_STAFF =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.STAFF);
    private static final Set<Role> DESK_AND_VIEWER =
            EnumSet.of(Role.SUPER_ADMIN, Role.BRANCH_ADMIN, Role.STAFF, Role.READONLY);

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
            // ★ 조회 전용에서 뺀다 — 성적 읽기 API 가 READONLY 에 열려 있지 않다
            //   (`exam-forms` · `students/{id}/grades` · `/grades/exams` · `/grades/trend` 전부 403).
            //   신상기록부·시간표와 같은 판단이다: 성적은 장학 취소 판정의 근거이고
            //   조회 전용의 실사용 범위가 아직 정해지지 않았다. 메뉴만 열면 빈 화면이 「고장」으로 읽힌다
            Map.entry("grade", WITH_TEACHER),

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
            // ★ 순찰은 사감(담임)이 도는 업무다. **`seat` 로 때우면 안 된다** —
            //   그러면 독서실 좌석배치·좌석 이탈·배정 관리까지 같이 열린다
            Map.entry("patrol", WITH_TEACHER),
            Map.entry("survey", WITH_TEACHER),
            // ★ 특강은 담임 업무가 아니다 — 출석 체크는 행정이 하고, API 도 관리자만 열려 있다.
            //   출석부가 **반 단위가 아니라 신청자 전원**이라 담임에게 열면 자기 반 밖 학생까지 보인다
            Map.entry("lecture", MANAGERS),

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

    /**
     * ★★ 이 역할들에 <b>기본으로 보이는 메뉴</b>인가 — <b>테이블을 읽는다</b>
     * (디랩 1029 8-2).
     *
     * <h2>★ 표에 없는 메뉴는 보인다</h2>
     * ⚠️ 새 메뉴가 <b>조용히 사라지면 기능이 없는 줄 안다.</b> 그래서
     * <b>설정이 하나도 없는 메뉴</b>는 전 역할에 보인다.
     *
     * <p>⚠️ <b>「행이 없다」와 「그 역할엔 안 보인다」는 다른 뜻</b>이다 —
     * 그 메뉴에 <b>다른 역할의 행이 있으면</b> 설정된 것이고, 이 역할엔 안 보인다.
     *
     * <p>★ {@link #BY_MENU} 는 <b>초기 데이터의 출처</b>로만 남는다 — 마이그레이션이
     * 그 값을 테이블에 넣었다. ⚠️ <b>판정은 더 이상 그것을 보지 않는다.</b>
     */
    public boolean visibleTo(String menuCode, Set<Role> roles) {
        if (!configuredCodes().contains(menuCode)) {
            return true;
        }
        Set<Role> allowed = allowedRoles().getOrDefault(menuCode, Set.of());
        return roles.stream().anyMatch(allowed::contains);
    }

    /** ★ 설정이 하나라도 있는 메뉴 코드. 캐시하지 않는다 — 편집이 바로 보여야 한다. */
    private Set<String> configuredCodes() {
        return new java.util.HashSet<>(repository.findConfiguredMenuCodes());
    }

    /** ★ 메뉴 코드 → 보이는 역할. */
    private Map<String, Set<Role>> allowedRoles() {
        Map<String, Set<Role>> out = new java.util.HashMap<>();
        for (var row : repository.findAllByDeletedFalse()) {
            out.computeIfAbsent(row.getMenu().getCode(), k -> new java.util.HashSet<>())
                    .add(row.getRole());
        }
        return out;
    }

    /**
     * ★ 초기 데이터가 무엇이었는지 — <b>마이그레이션과 대조</b>할 때 쓴다.
     *
     * <p>⚠️ 판정에는 쓰이지 않는다. 지우지 않는 이유는 <b>테이블이 비었을 때
     * 「원래 무엇이었나」에 답할 자리</b>가 필요해서다.
     */
    public static Map<String, Set<Role>> initialMatrix() {
        return BY_MENU;
    }
}
