package com.dlab.domain.lecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.web.Patch;
import com.dlab.common.security.Role;
import com.dlab.domain.lecture.entity.ApplicationStatus;
import com.dlab.domain.lecture.entity.Lecture;
import com.dlab.domain.lecture.entity.LectureStatus;
import com.dlab.domain.lecture.service.LectureService;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.Student;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * 특강 수강 대상·대기 상한 (디랩 1021 답변 Q2·Q3).
 *
 * <h2>★ 자격을 실제로 검사한다</h2>
 * 디랩이 「계열과 학년을 둘 다」로 확정했고, <b>표시만 하면 고3 대상 특강에 고1이
 * 신청한다.</b>
 *
 * <h2>★★ 비어 있으면 제한 없음이다</h2>
 * 「전체」를 행으로 만들면 「전체」와 「인문+자연 둘 다」가 <b>같은 뜻인데 데이터가
 * 두 가지로 갈린다.</b>
 */
@SpringBootTest
@Transactional
class LectureTargetTest {

    @Autowired LectureService lectureService;
    @Autowired EntityManager em;

    @MockitoBean com.dlab.domain.attendance.service.MissingAttendanceScheduler scheduler;

    static final short YEAR = 2090;

    Academy academy;
    AuthPrincipal admin;
    Lecture lecture;

    @BeforeEach
    void setUp() {
        academy = new Academy("LT", "대상테스트", LocalTime.of(9, 0));
        em.persist(academy);
        // ★ OPEN 은 담당 강사가 있어야 한다 — 없으면 접수를 시작할 수 없다
        var teacher = new com.dlab.domain.user.entity.Teacher(academy, "담당강사", "010-1111-2222");
        em.persist(teacher);
        em.flush();

        admin = AuthPrincipal.of(1L, "EMPLOYEE", academy.getId(),
                List.of(Role.BRANCH_ADMIN), false);

        lecture = lectureService.createFully(academy.getId(), YEAR,
                com.dlab.domain.lecture.entity.LectureType.LECTURE, "논술 특강",
                null, teacherId(), null, null, null,
                LocalDate.of(YEAR, 3, 1), LocalDate.of(YEAR, 3, 31),
                Instant.parse("2000-01-01T00:00:00Z"), Instant.parse("2099-01-01T00:00:00Z"),
                null, LectureStatus.OPEN, true, admin);
        em.flush();
    }

    private Long teacherId() {
        return em.createQuery("SELECT t.id FROM Teacher t WHERE t.academy = :a", Long.class)
                .setParameter("a", academy).getSingleResult();
    }

    private StudentEnrollment student(String no, GradeType grade, TrackType track) {
        Student s = new Student("LT-" + no, "학생" + no, "010-0000-" + no);
        em.persist(s);
        StudentEnrollment e = new StudentEnrollment(s, academy, YEAR, no, null, grade);
        if (track != null) {
            e.changeTrack(track);
        }
        em.persist(e);
        em.flush();
        return e;
    }

    @Test
    @DisplayName("★★ 대상이 비어 있으면 누구나 신청할 수 있다 — 「전체」를 행으로 만들지 않는다")
    void emptyTargetMeansNoRestriction() {
        var anyone = student("001", GradeType.HIGH2, null);

        assertThatCode(() -> lectureService.apply(lecture.getId(), anyone.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 두 축은 AND 다 — 「인문계열 고3」이면 둘 다 맞아야 신청된다")
    void bothAxesMustMatch() {
        lectureService.changeTargets(lecture.getId(),
                Set.of(TrackType.HUMANITIES), Set.of(GradeType.HIGH3), null, admin);
        em.flush();

        var humanitiesHigh3 = student("002", GradeType.HIGH3, TrackType.HUMANITIES);
        var scienceHigh3 = student("003", GradeType.HIGH3, TrackType.SCIENCE);
        var humanitiesHigh2 = student("004", GradeType.HIGH2, TrackType.HUMANITIES);

        assertThatCode(() -> lectureService.apply(lecture.getId(), humanitiesHigh3.getId()))
                .doesNotThrowAnyException();
        // 계열이 다르다
        assertThatThrownBy(() -> lectureService.apply(lecture.getId(), scienceHigh3.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("수강 대상");
        // 학년이 다르다
        assertThatThrownBy(() -> lectureService.apply(lecture.getId(), humanitiesHigh2.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("수강 대상");
    }

    @Test
    @DisplayName("★ 계열이 없는 학생은 계열 제한이 있는 특강에 신청할 수 없다")
    void studentWithoutTrackIsRejectedWhenTrackRestricted() {
        lectureService.changeTargets(lecture.getId(),
                Set.of(TrackType.SCIENCE), null, null, admin);
        em.flush();

        var noTrack = student("005", GradeType.HIGH3, null);

        assertThatThrownBy(() -> lectureService.apply(lecture.getId(), noTrack.getId()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("⚠️ 직원은 수강 대상에 들어가지 않는다 — 학년이 아니라 등록 구분값이다")
    void staffIsNotAcceptedAsTarget() {
        lectureService.changeTargets(lecture.getId(), null,
                Set.of(GradeType.HIGH3, GradeType.STAFF), null, admin);
        em.flush();
        em.clear();

        var saved = lectureService.sessions(lecture.getId(), admin);   // 영속성 확인용 호출
        assertThat(saved).isNotNull();
        assertThat(em.find(Lecture.class, lecture.getId()).getTargetGrades())
                .containsExactly(GradeType.HIGH3);
    }

    @Test
    @DisplayName("★★ 정원이 차면 대기로 넘어가고, 대기 상한을 넘으면 거절한다 — 현재 인원이 문구에 들어간다")
    void waitlistCapacityIsEnforced() {
        // 정원 1 · 대기 상한 1
        lectureService.update(lecture.getId(), null, null,
                null, com.dlab.common.web.Patch.of(1),
                null, null, null, null, null, null, null, admin);
        lectureService.changeTargets(lecture.getId(), null, null, Patch.of(1), admin);
        em.flush();

        var first = student("006", GradeType.HIGH3, null);
        var second = student("007", GradeType.HIGH3, null);
        var third = student("008", GradeType.HIGH3, null);

        assertThat(lectureService.apply(lecture.getId(), first.getId()).getStatus())
                .isEqualTo(ApplicationStatus.APPLIED);
        // 정원이 찼다 → 대기
        assertThat(lectureService.apply(lecture.getId(), second.getId()).getStatus())
                .isEqualTo(ApplicationStatus.WAITLISTED);
        em.flush();

        // 대기 상한도 찼다 → 거절. ★ 「가득 찼습니다」만 보여주면 몇 명인지 몰라 다시 눌러 본다
        assertThatThrownBy(() -> lectureService.apply(lecture.getId(), third.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("1/1");
    }

    @Test
    @DisplayName("★ 대기 상한을 안 두면 무제한이다 — 정원과 같은 규약이다")
    void nullWaitlistCapacityIsUnlimited() {
        lectureService.update(lecture.getId(), null, null,
                null, com.dlab.common.web.Patch.of(1),
                null, null, null, null, null, null, null, admin);
        em.flush();

        var first = student("009", GradeType.HIGH3, null);
        var second = student("010", GradeType.HIGH3, null);
        var third = student("011", GradeType.HIGH3, null);

        lectureService.apply(lecture.getId(), first.getId());
        lectureService.apply(lecture.getId(), second.getId());
        em.flush();

        assertThatCode(() -> lectureService.apply(lecture.getId(), third.getId()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("★★ 대기 상한을 비우는 요청이 실제로 비운다 — null 을 보내도 그대로 남던 버그")
    void waitlistCapacityCanBeCleared() {
        // 프론트 제보: {"waitlistCapacity": null} 을 보내도 200 이 오고 상한이 그대로 남았다.
        // 담당자는 「풀었다」고 알고 넘어간다. 0 은 「대기를 안 받는다」라 대신 쓸 수도 없다.
        lectureService.changeTargets(lecture.getId(), null, null, Patch.of(5), admin);
        em.flush();
        assertThat(em.find(Lecture.class, lecture.getId()).getWaitlistCapacity()).isEqualTo(5);

        // ★ 비워 달라 — Patch(null)
        lectureService.changeTargets(lecture.getId(), null, null, Patch.of(null), admin);
        em.flush();
        em.clear();
        assertThat(em.find(Lecture.class, lecture.getId()).getWaitlistCapacity()).isNull();
    }

    @Test
    @DisplayName("★ 안 보내면 유지된다 — 「안 보냄」과 「비워 달라」가 구분돼야 한다")
    void omittedWaitlistCapacityIsKept() {
        lectureService.changeTargets(lecture.getId(), null, null, Patch.of(5), admin);
        em.flush();

        // 키 생략 = null Patch 자체가 안 옴
        lectureService.changeTargets(lecture.getId(),
                Set.of(TrackType.SCIENCE), null, null, admin);
        em.flush();
        em.clear();

        assertThat(em.find(Lecture.class, lecture.getId()).getWaitlistCapacity()).isEqualTo(5);
    }
}
