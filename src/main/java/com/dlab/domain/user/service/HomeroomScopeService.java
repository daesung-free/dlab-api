package com.dlab.domain.user.service;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Account;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.entity.Teacher;
import com.dlab.domain.user.repository.AccountRepository;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.ClassMasterRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 담임(TEACHER)의 조회 범위를 반 단위로 좁힌다.
 *
 * <h2>왜 필요한가</h2>
 * 담임 계정은 {@code /students}·{@code /classes}에서 {@code @PreAuthorize}로 아예 막혀 있는데,
 * 출결·사유신청·상벌점은 담임이 매일 쓰는 화면이라 열려 있다. 그런데 그 세 곳이 <b>지점 전체</b>를
 * 내려주고 있었다 — 담당이 아닌 반 학생의 이름·연락처·출결이 그대로 보인다.
 * {@code classId} 파라미터가 있긴 하나 그건 <b>클라이언트가 고르는 필터</b>라, 빼고 부르면 전체가 온다.
 *
 * <p>그래서 범위를 <b>서버가</b> 정한다. 화면에서 반 선택을 감추는 것으로는 막을 수 없다 —
 * 토큰만 있으면 그대로 호출된다(CLAUDE.md §7).
 *
 * <h2>반환값 읽는 법 — {@code empty()}와 빈 집합은 다르다</h2>
 * <ul>
 *   <li>{@link Optional#empty()} — <b>제한 없음.</b> 담임이 아닌 계정(본사·지점관리자·행정)이다</li>
 *   <li>비어 있는 {@code Set} — <b>담임인데 맡은 반이 없다.</b> 아무것도 못 본다</li>
 * </ul>
 * 이 둘을 같게 다루면 정반대가 된다 — 맡은 반이 없는 담임에게 지점 전체가 열린다.
 *
 * <h2>반이 아니라 배정(enrollment)으로 거르는 이유</h2>
 * 출결·상벌점 행은 반이 아니라 <b>학생</b>에 붙는다. 반 미배정 학생은 {@code classId}가 없어
 * 반으로 거르면 조용히 빠지는데, 담임 입장에서는 원래 안 보여야 하는 학생이라 그게 맞다.
 * 대신 판정을 한 곳에 모아 두려고 {@link #enrollmentIdsOf}로 학생 집합까지 같이 낸다.
 */
@Service
@RequiredArgsConstructor
public class HomeroomScopeService {

    private final AccountRepository accountRepository;
    private final ClassMasterRepository classMasterRepository;
    private final ClassAssignmentRepository classAssignmentRepository;

    /**
     * 이 계정이 볼 수 있는 반. 담임이 아니면 {@code empty()}(제한 없음)다.
     *
     * @param year 조회 연도. 반은 연도마다 새로 만들어지므로 해를 넘기면 다른 반이다
     */
    @Transactional(readOnly = true)
    public Optional<Set<Long>> classIdsOf(AuthPrincipal me, short year) {
        if (!isHomeroomOnly(me)) {
            return Optional.empty();
        }
        return Optional.of(teacherIdOf(me)
                .map(teacherId -> classMasterRepository.search(me.academyId(), year).stream()
                        .filter(c -> isHomeroomOf(c, teacherId))
                        .map(ClassMaster::getId)
                        .collect(Collectors.toSet()))
                .orElseGet(Set::of));
    }

    /**
     * 이 계정이 볼 수 있는 학생(등록 건). 담임이 아니면 {@code empty()}(제한 없음)다.
     *
     * <p>반 미배정 학생은 어느 담임에게도 안 잡힌다 — 담임이 없는 학생이라 맞는 동작이다.
     */
    @Transactional(readOnly = true)
    public Optional<Set<Long>> enrollmentIdsOf(AuthPrincipal me, short year) {
        return classIdsOf(me, year).map(classIds -> classIds.stream()
                .flatMap(classId -> classAssignmentRepository.findActiveByClassId(classId).stream())
                .map(a -> a.getEnrollment().getId())
                .collect(Collectors.toSet()));
    }

    /**
     * 실제로 적용할 반 필터. 담임이면 맡은 반으로 좁혀지고, 남의 반 번호를 직접 넣어도 통하지 않는다.
     *
     * <p>★ <b>반환값의 {@code empty()}와 빈 집합을 반드시 구분해서 받아야 한다.</b>
     * 둘을 같게 다루면 <b>정반대</b>가 된다 — 맡은 반이 없는 담임과 남의 반을 찍은 담임에게
     * 지점 전체가 열린다. {@link ClassFilter#matches(Long)}를 쓰면 이 실수를 할 수 없다.
     */
    @Transactional(readOnly = true)
    public ClassFilter resolveClassFilter(AuthPrincipal me, short year, Long requestedClassId) {
        Optional<Set<Long>> allowed = classIdsOf(me, year);
        if (allowed.isEmpty()) {
            // 담임이 아니다 — 화면이 고른 반만 반영한다
            return requestedClassId == null ? ClassFilter.unrestricted()
                    : ClassFilter.only(Set.of(requestedClassId));
        }
        Set<Long> mine = allowed.get();
        if (requestedClassId == null) {
            return ClassFilter.only(mine);
        }
        // 남의 반을 지정하면 빈 결과다 — 403으로 막으면 "그 반이 존재한다"는 사실이 새어나간다
        return ClassFilter.only(mine.contains(requestedClassId) ? Set.of(requestedClassId) : Set.of());
    }

    /**
     * 반 필터. "제한 없음"과 "볼 수 있는 반이 없음"을 타입으로 갈라 둔다 —
     * {@code Set}만 돌려주면 빈 집합이 두 뜻을 겸해 권한이 거꾸로 열린다.
     */
    public record ClassFilter(boolean restricted, Set<Long> classIds) {

        public static ClassFilter unrestricted() {
            return new ClassFilter(false, Set.of());
        }

        public static ClassFilter only(Set<Long> classIds) {
            return new ClassFilter(true, Set.copyOf(classIds));
        }

        /** 이 반이 보이는가. 제한이 없으면 {@code classId}가 {@code null}이어도 통과다. */
        public boolean matches(Long classId) {
            if (!restricted) {
                return true;
            }
            return classId != null && classIds.contains(classId);
        }

        /** 볼 수 있는 반이 하나도 없는가. 이때는 조회 자체를 건너뛸 수 있다. */
        public boolean blocksEverything() {
            return restricted && classIds.isEmpty();
        }
    }

    /**
     * 담임 범위를 적용받는 계정인지.
     *
     * <p>겸직이 없다는 전제다(CLAUDE.md §2) — 한 계정이 담임이면서 지점 관리자인 경우는 없다.
     * 그래도 상위 역할이 함께 있으면 <b>넓은 쪽을 따른다</b>. 좁은 쪽을 따르면 권한을 더 준 사람이
     * 오히려 못 보게 된다.
     */
    private boolean isHomeroomOnly(AuthPrincipal me) {
        if (me.allAcademy()) {
            return false;
        }
        boolean elevated = me.hasRole(Role.SUPER_ADMIN)
                || me.hasRole(Role.BRANCH_ADMIN)
                || me.hasRole(Role.STAFF);
        return !elevated && me.hasRole(Role.TEACHER);
    }

    /** JWT에는 teacher_id가 없어 계정에서 한 번 꺼낸다. */
    private Optional<Long> teacherIdOf(AuthPrincipal me) {
        return accountRepository.findById(me.accountId())
                .map(Account::getTeacher)
                .map(Teacher::getId);
    }

    private boolean isHomeroomOf(ClassMaster c, Long teacherId) {
        return c.getHomeroomTeacher() != null
                && teacherId.equals(c.getHomeroomTeacher().getId());
    }

    /** 목록 필터용. {@code empty()}면 전부 통과다. */
    public static boolean allows(Optional<Set<Long>> scope, Long id) {
        return scope.isEmpty() || (id != null && scope.get().contains(id));
    }

    /** 여러 곳에서 같은 모양으로 쓰라고 둔다 — 반 목록을 리스트로 받는 쪽. */
    public static List<Long> asList(Optional<Set<Long>> scope) {
        return scope.map(List::copyOf).orElseGet(List::of);
    }
}
