package com.dlab.domain.user.repository;

import com.dlab.domain.user.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** 목록 화면이 직원마다 계정을 조회하지 않게 한 번에 받는다. */
    @Query("SELECT a FROM Account a WHERE a.teacher.id IN :teacherIds AND a.deleted = false")
    List<Account> findByTeacherIds(@Param("teacherIds") java.util.Collection<Long> teacherIds);

    @Query("SELECT a FROM Account a WHERE a.employee.id IN :employeeIds AND a.deleted = false")
    List<Account> findByEmployeeIds(@Param("employeeIds") java.util.Collection<Long> employeeIds);
}
