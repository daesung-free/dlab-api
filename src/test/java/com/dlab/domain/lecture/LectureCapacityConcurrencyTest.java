package com.dlab.domain.lecture;

import com.dlab.domain.lecture.entity.ApplicationStatus;
import com.dlab.domain.lecture.entity.Lecture;
import com.dlab.domain.lecture.entity.LectureStatus;
import com.dlab.domain.lecture.entity.LectureType;
import com.dlab.domain.lecture.repository.LectureApplicationRepository;
import com.dlab.domain.lecture.repository.LectureRepository;
import com.dlab.domain.lecture.service.LectureService;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 특강 정원 — 동시 신청.
 *
 * <p><b>★ {@code @Transactional}을 붙이지 않는다.</b> 테스트 트랜잭션이 열려 있으면 워커
 * 스레드가 그 안을 못 보고 커밋도 안 돼서, <b>행 락이 실제로 동작하는지 검증하지 못한다.</b>
 * 대신 정리를 직접 한다.
 *
 * <p>같은 유형의 결함을 기숙사 정원에서 실제로 겪었다 — "인원 세기 → 정원 비교 → INSERT"가
 * 동시 요청에 뚫려 <b>2인실에 8명</b>이 들어갔다. 여기서 다시 확인한다.
 */
@SpringBootTest
class LectureCapacityConcurrencyTest {

    private static final int THREADS = 8;
    private static final int CAPACITY = 3;
    private static final short YEAR = 2026;
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired LectureService lectureService;
    @Autowired LectureRepository lectureRepository;
    @Autowired LectureApplicationRepository applicationRepository;
    @Autowired AcademyRepository academyRepository;
    @Autowired StudentRepository studentRepository;
    @Autowired StudentEnrollmentRepository enrollmentRepository;
    @Autowired TransactionTemplate tx;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    Long academyId;
    Long lectureId;
    List<Long> enrollmentIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx.executeWithoutResult(status -> {
            int seq = SEQ.incrementAndGet();
            Academy academy = academyRepository.save(new Academy(
                    "LX" + seq + System.nanoTime() % 10_000, "특강동시성지점", LocalTime.of(9, 0)));
            academyId = academy.getId();

            Lecture lecture = new Lecture(academy, YEAR, LectureType.LECTURE, "정원테스트특강");
            lecture.update(null, null, CAPACITY, null, null, null, null, null);
            lecture.changeStatus(LectureStatus.OPEN);
            lectureId = lectureRepository.save(lecture).getId();

            for (int i = 0; i < THREADS; i++) {
                Student student = studentRepository.save(
                        new Student("LX" + seq + "-" + i, "동시신청" + i, "010-0000-0000"));
                StudentEnrollment enrollment = enrollmentRepository.save(new StudentEnrollment(
                        student, academy, YEAR, "2026-%04d".formatted(i + 1), null, GradeType.N_SU));
                enrollmentIds.add(enrollment.getId());
            }
        });
    }

    /** 실제로 커밋되므로 직접 지운다. FK 역순. */
    @AfterEach
    void tearDown() {
        tx.executeWithoutResult(status -> {
            em.createNativeQuery("DELETE FROM lecture_attendance WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("DELETE FROM lecture_application WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("DELETE FROM lecture_session WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            em.createNativeQuery("DELETE FROM lecture WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();

            @SuppressWarnings("unchecked")
            List<Number> studentIds = em.createNativeQuery(
                            "SELECT student_id FROM student_enrollment WHERE academy_id = :id")
                    .setParameter("id", academyId).getResultList();
            em.createNativeQuery("DELETE FROM student_enrollment WHERE academy_id = :id")
                    .setParameter("id", academyId).executeUpdate();
            if (!studentIds.isEmpty()) {
                em.createNativeQuery("DELETE FROM student WHERE id IN (:ids)")
                        .setParameter("ids", studentIds.stream().map(Number::longValue).toList())
                        .executeUpdate();
            }
            em.createNativeQuery("DELETE FROM academy WHERE id = :id")
                    .setParameter("id", academyId).executeUpdate();
        });
    }

    @Test
    @DisplayName("★ 8명이 동시에 신청해도 확정은 정원(3명)을 넘지 않는다 — 나머지는 대기다")
    void capacityHoldsUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(THREADS);

        for (Long enrollmentId : enrollmentIds) {
            pool.submit(() -> {
                try {
                    start.await();
                    lectureService.apply(lectureId, enrollmentId);
                } catch (Exception ignored) {
                    // 실패해도 된다 — 확정 인원이 정원을 넘지 않는 것만 본다
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        long confirmed = applicationRepository.countByLectureIdAndStatus(
                lectureId, ApplicationStatus.APPLIED);
        long waitlisted = applicationRepository.countByLectureIdAndStatus(
                lectureId, ApplicationStatus.WAITLISTED);

        // 락이 없으면 여기가 8이 된다 — 기숙사에서 실제로 그랬다
        assertThat(confirmed).isEqualTo(CAPACITY);
        // 신청 자체는 아무도 잃지 않는다
        assertThat(confirmed + waitlisted).isEqualTo(THREADS);
    }
}
