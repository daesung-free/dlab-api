package com.dlab.domain;

import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.*;
import com.dlab.domain.user.service.ClassService;
import com.dlab.domain.user.service.StudentService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동시성 — 여러 요청이 <b>같은 순간</b> 들어올 때의 동작.
 *
 * <p><b>★ 이 클래스에는 {@code @Transactional}을 붙이지 않는다.</b> 테스트 트랜잭션이 열려 있으면
 * 워커 스레드가 그 안을 못 보고, 커밋이 안 돼서 유니크 제약이 실제로 걸리지 않는다 —
 * 동시성 테스트가 통과해도 아무것도 검증하지 못하는 상태가 된다. 대신 정리를 직접 한다.
 *
 * <p>여기서 확인하는 것은 <b>애플리케이션 로직이 아니라 DB 제약이 최후 방어선으로 동작하는가</b>다.
 * 다중 인스턴스에서는 애플리케이션 락이 무의미하므로, 결국 유니크 제약만이 막는다.
 */
@SpringBootTest
class ConcurrencyTest {

    private static final int THREADS = 8;

    /** 지점 코드 충돌 방지용. {@code acad_cd}가 UNIQUE라 테스트끼리 겹치면 셋업이 깨진다. */
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired StudentService studentService;
    @Autowired ClassService classService;
    @Autowired AcademyRepository academyRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired StudentEnrollmentRepository enrollmentRepository;
    @Autowired ClassMasterRepository classMasterRepository;
    @Autowired ClassAssignmentRepository classAssignmentRepository;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Long academyId;
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        academyId = tx.execute(status -> {
            Academy academy = academyRepository.save(
                    // acad_cd는 UNIQUE다. 테스트마다 새 지점을 만들므로 겹치지 않을 값을 쓴다
                    new Academy("CC" + SEQ.incrementAndGet() + System.nanoTime() % 100_000,
                            "동시성테스트지점",
                            java.time.LocalTime.of(9, 0)));
            return academy.getId();
        });
        principal = AuthPrincipal.of(0L, "EMPLOYEE", academyId,
                List.of(Role.BRANCH_ADMIN), false);
    }

    /**
     * 트랜잭션이 실제로 커밋되므로 직접 지운다.
     *
     * <p><b>FK 역순으로 지워야 한다.</b> 특히 {@code student}는 {@code student_enrollment}가
     * 참조하므로 <b>등록 건을 먼저 지우되, 사람 ID는 그 전에 확보</b>해야 한다 —
     * 등록 건을 지운 뒤에는 어느 사람이 이 지점 소속이었는지 알 방법이 없다.
     */
    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            @SuppressWarnings("unchecked")
            List<Number> studentIds = em.createNativeQuery(
                            "SELECT student_id FROM student_enrollment WHERE academy_id = :id")
                    .setParameter("id", academyId).getResultList();

            for (String table : List.of("class_assignment")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE academy_id = :id")
                        .setParameter("id", academyId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM student_enrollment WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            if (!studentIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM student WHERE id IN (:ids)")
                        .setParameter("ids", studentIds.stream().map(Number::longValue).toList())
                        .executeUpdate();
            }
            for (String table : List.of("class_master")) {
                em.createNativeQuery("DELETE FROM " + table + " WHERE academy_id = :id")
                        .setParameter("id", academyId).executeUpdate();
            }
            em.createNativeQuery("DELETE FROM academy WHERE id = :id")
                    .setParameter("id", academyId).executeUpdate();
        });
    }

    /** 모든 스레드를 같은 순간에 출발시킨다 — 순차 실행이면 동시성을 검증하지 못한다. */
    private int runConcurrently(int threads, Runnable task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger succeeded = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    task.run();
                    succeeded.incrementAndGet();
                } catch (Exception ignored) {
                    // 실패는 여기서 세지 않는다 — 실패해야 정상인 테스트가 있다
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        return succeeded.get();
    }

    @Test
    @DisplayName("★ 동시 접수해도 학번이 중복되지 않는다 — 매년 초 대량 접수에서 실제로 부딪히는 경로")
    void studentNoNeverDuplicates() throws Exception {
        int succeeded = runConcurrently(THREADS, () ->
                studentService.admit(academyId, (short) 2026, "동시접수", "010-0000-0000",
                        GradeType.N_SU, TrackType.SCIENCE, principal));

        List<String> numbers = tx.execute(status -> em.createQuery("""
                SELECT e.studentNo FROM StudentEnrollment e
                WHERE e.academy.id = :id AND e.year = 2026
                """, String.class).setParameter("id", academyId).getResultList());

        // 성공한 만큼 행이 있고, 학번이 전부 다르다
        assertThat(numbers).hasSize(succeeded);
        assertThat(numbers).doesNotHaveDuplicates();
        // 재시도가 붙어 있으므로 대부분 성공해야 한다. 전멸하면 채번 자체가 고장난 것이다
        assertThat(succeeded).isGreaterThan(THREADS / 2);
    }

    @Test
    @DisplayName("★ 같은 학생을 동시에 여러 반에 배정해도 활성 배정은 하나뿐이다")
    void classAssignmentStaysSingle() throws Exception {
        Long enrollmentId = tx.execute(status -> createStudent("반배정대상"));
        List<Long> classIds = tx.execute(status -> {
            Academy academy = academyRepository.findById(academyId).orElseThrow();
            return java.util.stream.IntStream.range(0, THREADS)
                    .mapToObj(i -> classMasterRepository.save(new ClassMaster(
                            academy, (short) 2026, "반" + i, ClassType.FIXED, null)).getId())
                    .toList();
        });

        AtomicInteger index = new AtomicInteger();
        runConcurrently(THREADS, () -> {
            Long classId = classIds.get(index.getAndIncrement() % classIds.size());
            classService.assignStudent(classId, enrollmentId, principal);
        });

        Long active = tx.execute(status -> em.createQuery("""
                SELECT COUNT(a) FROM ClassAssignment a
                WHERE a.enrollment.id = :id AND a.active = true
                """, Long.class).setParameter("id", enrollmentId).getSingleResult());

        // 부분 유니크 인덱스가 최후 방어선이다 — 애플리케이션 락은 다중 인스턴스에서 무의미하다
        assertThat(active).isEqualTo(1);
    }


    @Transactional(propagation = Propagation.MANDATORY)
    Long createStudent(String name) {
        Academy academy = academyRepository.findById(academyId).orElseThrow();
        Student student = studentRepository.save(
                new Student("CC" + System.nanoTime(), name, "010-0000-0000"));
        return enrollmentRepository.save(new StudentEnrollment(
                student, academy, (short) 2026, null, null, GradeType.N_SU)).getId();
    }
}
