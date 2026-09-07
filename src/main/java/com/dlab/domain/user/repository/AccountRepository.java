package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    Optional<Account> findByLoginId(String loginId);

    /**
     * 학생 재가입 중복 검사.
     * loginId UNIQUE만으로는 부족하다 — 승인 대기(PENDING) 중인 기존 요청도 "이미 신청한 사람"이다.
     */
    @Query("""
            SELECT COUNT(a) > 0 FROM Account a
            WHERE a.loginId = :loginId
              AND a.status <> com.dlab.domain.user.entity.AccountStatus.WITHDRAWN
              AND a.deleted = false
            """)
    boolean existsActiveOrPendingByLoginId(String loginId);

    /** 해당 학생(사람)에 연결된 학부모 계정들. 알림 수신자 조회에 쓴다. */
    @Query("""
            SELECT a FROM Account a
            JOIN FETCH a.guardian g
            WHERE a.accountType = com.dlab.domain.user.entity.AccountType.PARENT
              AND a.deleted = false
              AND EXISTS (
                    SELECT 1 FROM StudentGuardianLink l
                    WHERE l.guardian = g AND l.student.id = :studentId)
            """)
    List<Account> findGuardianAccountsOfStudent(Long studentId);

    /** 학생 본인 계정. */
    Optional<Account> findByStudentId(Long studentId);

    Optional<Account> findByEmployeeId(Long employeeId);

    Optional<Account> findByTeacherId(Long teacherId);

    /**
     * 계정 ID → 표시 이름.
     *
     * <p>직원·선생님·학생 어디에 붙었는지에 따라 이름의 출처가 다르다. 화면이
     * "누가 했나"를 이름으로 보여주려면 여기서 한 번에 풀어야 한다 —
     * 행마다 조회하면 목록 크기만큼 쿼리가 나간다.
     *
     * @return {@code [accountId, name]}
     */
    @Query("""
            SELECT a.id,
                   COALESCE(e.name, t.name, s.name)
            FROM Account a
            LEFT JOIN a.employee e
            LEFT JOIN a.teacher t
            LEFT JOIN a.student s
            WHERE a.id IN :ids
            """)
    java.util.List<Object[]> findDisplayNames(@org.springframework.data.repository.query.Param("ids")
                                              java.util.Collection<Long> ids);

    /**
     * 관리자 계정 목록 (F-4.10-2 사용자 관리).
     *
     * <p><b>{@code STUDENT}·{@code PARENT}는 뺀다.</b> 이 화면은 직원·강사 계정과 권한을
     * 다루는 곳이고, 학생·학부모 계정은 가입 승인(F-4.12-1)에서 따로 관리한다 —
     * 섞으면 목록이 수백 건이 되어 관리자를 찾을 수 없다.
     *
     * <p>지점은 소속(직원/선생님)에서 나온다. <b>계정 자체에는 지점이 없다</b> —
     * 그래서 소속이 없는 계정은 지점 필터에 걸리지 않는다.
     */
    @Query("""
            SELECT a FROM Account a
            LEFT JOIN FETCH a.employee e
            LEFT JOIN FETCH e.academy
            LEFT JOIN FETCH a.teacher t
            LEFT JOIN FETCH t.academy
            WHERE a.accountType IN (com.dlab.domain.user.entity.AccountType.EMPLOYEE,
                                    com.dlab.domain.user.entity.AccountType.TEACHER)
              AND a.deleted = false
              AND (:academyId IS NULL
                   OR e.academy.id = :academyId OR t.academy.id = :academyId)
              AND (:status IS NULL OR a.status = :status)
            ORDER BY a.id DESC
            """)
    List<Account> findStaffAccounts(
            @org.springframework.data.repository.query.Param("academyId") Long academyId,
            @org.springframework.data.repository.query.Param("status")
            com.dlab.domain.user.entity.AccountStatus status);
}
