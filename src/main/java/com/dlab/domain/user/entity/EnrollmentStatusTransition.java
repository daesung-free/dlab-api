package com.dlab.domain.user.entity;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 재원 상태 전이 규칙 (P1-04).
 *
 * <p>규칙 세 줄:
 * <ol>
 *   <li>재원 ↔ 휴원은 자유롭다.</li>
 *   <li>어느 상태에서든 종료 상태(퇴원·제적·수료)로 갈 수 있다.</li>
 *   <li><b>종료 상태에서는 재원으로만 돌아온다.</b> 종료끼리는 못 넘어간다.</li>
 * </ol>
 *
 * <p><b>3번이 핵심이다.</b> "퇴원을 제적으로 정정"하려면 재원을 한 번 거쳐야 한다.
 * 곧바로 바꾸게 두면 이력에 {@code WITHDRAWN → EXPELLED} 한 줄만 남아 <b>왜</b> 바뀌었는지
 * 알 수 없는데, 재원을 거치면 "퇴원 취소 → 제적 처리" 두 줄이 남아 정정임이 드러난다.
 * 환불·재등록 분쟁에서 실제로 따지게 되는 지점이다.
 *
 * <p><b>종료 → 재원은 착오 정정 전용이다. 재등록이 아니다.</b> 여기는 1년 코호트라
 * 재등록은 <b>새 등록 건(행)을 추가</b>하는 것이지 지난 등록 건을 되살리는 게 아니다
 * (CLAUDE.md §3). 되살리면 그해 출결·상벌점이 새 기수에 섞인다.
 */
public final class EnrollmentStatusTransition {

    private static final Map<EnrollmentStatus, Set<EnrollmentStatus>> ALLOWED =
            new EnumMap<>(EnrollmentStatus.class);

    static {
        ALLOWED.put(EnrollmentStatus.ENROLLED, EnumSet.of(
                EnrollmentStatus.ON_LEAVE,
                EnrollmentStatus.WITHDRAWN,
                EnrollmentStatus.EXPELLED,
                EnrollmentStatus.GRADUATED));

        ALLOWED.put(EnrollmentStatus.ON_LEAVE, EnumSet.of(
                EnrollmentStatus.ENROLLED,
                EnrollmentStatus.WITHDRAWN,
                EnrollmentStatus.EXPELLED,
                EnrollmentStatus.GRADUATED));

        // 종료 상태 → 재원(착오 정정)만. 종료끼리는 막는다.
        Set<EnrollmentStatus> revertOnly = EnumSet.of(EnrollmentStatus.ENROLLED);
        ALLOWED.put(EnrollmentStatus.WITHDRAWN, revertOnly);
        ALLOWED.put(EnrollmentStatus.EXPELLED, revertOnly);
        ALLOWED.put(EnrollmentStatus.GRADUATED, revertOnly);
    }

    private EnrollmentStatusTransition() {
    }

    /** 같은 상태로의 전이는 허용하지 않는다 — 이력에 노이즈만 쌓인다. */
    public static boolean isAllowed(EnrollmentStatus from, EnrollmentStatus to) {
        if (from == null || to == null || from == to) {
            return false;
        }
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<EnrollmentStatus> allowedFrom(EnrollmentStatus from) {
        return Set.copyOf(ALLOWED.getOrDefault(from, Set.of()));
    }
}
