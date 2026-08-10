package com.dlab.api.app.home;

import com.dlab.domain.notice.entity.Notice;
import com.dlab.domain.routine.entity.RoutineResultStatus;
import com.dlab.domain.routine.service.DailyRoutineService;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import java.time.Instant;
import java.util.List;

/** 앱 홈 한 화면. */
public record HomeResponse(Profile profile,
                           Metrics metrics,
                           List<Routine> todayRoutines,
                           List<Banner> banners) {

    /**
     * 상단 프로필.
     *
     * <p><b>학생 고유ID는 여기 없다</b> — 마이페이지에만 노출한다. 홈은 학부모도 보는
     * 화면이라, 자녀 화면에서 그대로 보이면 노출 지점이 늘어난다.
     */
    public record Profile(Long enrollmentId, String studentName, String studentNo,
                          GradeType grade, String academyName, String className) {

        static Profile of(StudentEnrollment e, String className) {
            return new Profile(e.getId(), e.getStudent().getName(), e.getStudentNo(),
                    e.getGrade(), e.getAcademy().getAcadNm(), className);
        }
    }

    /**
     * 지표.
     *
     * <p>순공시간은 <b>확정된 날의 합</b>이라 오늘 몫은 다음날 반영된다.
     * {@code confirmedDays}를 함께 내려, 화면이 "며칠 기준인지"를 밝힐 수 있게 한다 —
     * 안 밝히면 월초에 출석률 100%가 이상하게 보인다.
     *
     * @param attendanceRate 확정된 날이 없으면 {@code null}
     */
    public record Metrics(int weeklyStudyMinutes, int monthlyStudyMinutes,
                          Integer attendanceRate, int confirmedDays) {
    }

    /** 오늘의 데일리 루틴. 점수는 <b>공개된 것만</b> 내려온다(검수 중인 값은 감춘다). */
    public record Routine(Long routineId, String name, String subject,
                          RoutineResultStatus status, Short score, Short maxScore) {

        static Routine from(DailyRoutineService.TodayRoutine t) {
            return new Routine(t.routine().getId(), t.routine().getName(),
                    t.routine().getSubject(), t.status(), t.visibleScore(),
                    t.routine().getMaxScore());
        }
    }

    /** 홈 배너 (F-4.12-3). 공지 도메인이 원본이다. */
    public record Banner(Long noticeId, String title, Instant postedAt) {

        static Banner from(Notice n) {
            return new Banner(n.getId(), n.getTitle(),
                    n.getPublishedAt() != null ? n.getPublishedAt() : n.getCreatedAt());
        }
    }
}
