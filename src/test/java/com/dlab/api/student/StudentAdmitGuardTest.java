package com.dlab.api.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import com.dlab.domain.user.service.StudentService;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 신규 접수의 <b>중복 제출 방어</b> (점검표 경계 24번).
 *
 * <p>★ {@code @Transactional} 을 붙이지 않는다. {@code admit} 이 채번 재시도 때문에
 * <b>새 트랜잭션</b>을 열기 때문에, 테스트가 트랜잭션을 잡고 있으면 방금 넣은 행이
 * 그 트랜잭션 밖에서 안 보여 중복 판정이 성립하지 않는다. 대신 뒷정리를 직접 한다.
 */
@SpringBootTest
class StudentAdmitGuardTest {

    @Autowired StudentService studentService;
    @Autowired EntityManager em;
    @Autowired Clock clock;
    @Autowired org.springframework.transaction.support.TransactionTemplate tx;
    @Autowired com.dlab.domain.user.repository.AcademyRepository academyRepository;

    @MockitoBean
    com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Academy academy;
    AuthPrincipal admin;

    /** 테스트마다 다른 코드를 쓴다 — 커밋된 채 남아 다음 테스트와 유니크 충돌이 난다 */
    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(900);

    @BeforeEach
    void setUp() {
        // admit 이 새 트랜잭션을 열어 읽으므로 지점도 <b>커밋된 상태</b>여야 한다
        String code = String.valueOf(SEQ.incrementAndGet());
        academy = tx.execute(status ->
                academyRepository.save(new Academy(code, "중복테스트" + code, LocalTime.of(9, 0))));
        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        // 커밋해 넣었으니 직접 지운다 — 안 지우면 다음 실행의 시드·통계에 섞인다
        tx.executeWithoutResult(status -> em.createNativeQuery(
                        "DELETE FROM student_enrollment WHERE academy_id = ?1")
                .setParameter(1, academy.getId()).executeUpdate());
        tx.executeWithoutResult(status -> em.createNativeQuery(
                        "DELETE FROM student WHERE id NOT IN (SELECT student_id FROM student_enrollment)")
                .executeUpdate());
        tx.executeWithoutResult(status -> em.createNativeQuery(
                        "DELETE FROM academy WHERE id = ?1")
                .setParameter(1, academy.getId()).executeUpdate());
    }

    private StudentEnrollment admit(String name, String phone, LocalDate birth) {
        return studentService.admit(academy.getId(), (short) 2026, name, phone,
                GradeType.N_SU, null, TrackType.SCIENCE, birth, "M", null, null, null, admin);
    }

    @Test
    @DisplayName("★ 같은 내용을 두 번 보내면 두 번째가 막힌다 — 저장 버튼 연타")
    void secondSubmitIsRejected() {
        admit("중복홍길동", "010-1111-2222", LocalDate.of(2007, 3, 1));

        assertThatThrownBy(() -> admit("중복홍길동", "010-1111-2222", LocalDate.of(2007, 3, 1)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", ErrorCode.DUPLICATE_ADMISSION);
    }

    @Test
    @DisplayName("★★ 동시에 여러 번 보내도 한 명만 생긴다 — 버튼 연타는 거의 동시에 도착한다")
    void concurrentSubmitsCreateOnlyOne() throws Exception {
        int threads = 5;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        var start = new java.util.concurrent.CountDownLatch(1);
        var created = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    admit("동시등록", "010-9999-1111", LocalDate.of(2007, 3, 1));
                    created.incrementAndGet();
                } catch (BusinessException e) {
                    if (e.getErrorCode() == ErrorCode.DUPLICATE_ADMISSION) {
                        rejected.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        // ★ 읽고-쓰는 사이를 막지 않으면 여기서 5가 나온다. 자문 잠금으로 직렬화한다
        assertThat(created.get()).isEqualTo(1);
        assertThat(rejected.get()).isEqualTo(threads - 1);
    }

    @Test
    @DisplayName("동명이인은 그대로 등록된다 — 연락처가 다르면 다른 사람이다")
    void sameNameDifferentPersonIsAllowed() {
        admit("동명이인", "010-1111-3333", LocalDate.of(2007, 3, 1));

        StudentEnrollment second = admit("동명이인", "010-4444-5555", LocalDate.of(2006, 5, 2));

        assertThat(second.getStudentNo()).isNotNull();
    }

    @Test
    @DisplayName("성별이 잘못되면 「학번 채번 실패」가 아니라 그 사유가 나온다")
    void invalidGenderReportsItsOwnCause() {
        assertThatThrownBy(() -> studentService.admit(academy.getId(), (short) 2026,
                "성별오류", null, GradeType.N_SU, null, TrackType.SCIENCE, null,
                "X", null, null, null, admin))
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("학번 채번"));
    }
}
