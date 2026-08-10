package com.dlab.api.app.home;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.attendance.entity.DailyStatus;
import com.dlab.domain.attendance.service.AttendanceQueryService;
import com.dlab.domain.notice.service.NoticeService;
import com.dlab.domain.routine.service.DailyRoutineService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.service.AppScopeResolver;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 앱 홈 (A-3).
 *
 * <p>부팅 직후 첫 화면이라 <b>한 번의 호출로 화면이 완성돼야 한다</b> — 프로필·지표·배너를
 * 따로 부르면 왕복이 네 번이고, 그 사이 값이 서로 다른 시점의 것이 된다.
 *
 * <h2>조합만 하고 규칙은 도메인에 둔다</h2>
 * 출결·루틴·공지 서비스를 불러 붙이기만 한다. 홈 화면은 앱 전용이라 다른 클라이언트가
 * 다시 쓸 조합이 아니고, 실제 계산(순공시간·루틴 공개 여부)은 전부 도메인 쪽에 있다.
 *
 * <h2>★ "다짐 한마디"는 넣지 않았다</h2>
 * 시트에 항목만 있고 <b>누가 쓰는지·주기·노출 위치가 정의되지 않았다</b>(미확정).
 * 임의로 만들면 화면이 먼저 굳어 나중에 뜯게 된다 — 확정되면 필드만 더한다.
 *
 * <p><b>퀵메뉴 8개도 서버가 내리지 않는다.</b> 화면 이동 경로일 뿐 서버 상태가 아니다.
 */
@RestController
@RequestMapping("/api/v1/app/home")
@RequiredArgsConstructor
public class AppHomeController {

    private final AppScopeResolver scopeResolver;
    private final AttendanceQueryService attendanceQueryService;
    private final DailyRoutineService routineService;
    private final NoticeService noticeService;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final Clock clock;

    /**
     * @param studentId <b>학부모만</b> 쓴다. 계정 하나에 자녀가 여럿이라 서버가 고를 수 없다
     */
    @GetMapping
    public ApiResponse<HomeResponse> home(@CurrentAccount AuthPrincipal me,
                                          @RequestParam(required = false) Long studentId) {

        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        LocalDate today = LocalDate.now(clock);

        String className = classAssignmentRepository
                .findActiveFixedByEnrollmentId(enrollment.getId())
                .map(a -> a.getClassMaster().getName())
                .orElse(null);

        List<AttendanceQueryService.DailySummary> week = attendanceQueryService.daily(
                enrollment.getId(), today.with(DayOfWeek.MONDAY), today);
        List<AttendanceQueryService.DailySummary> month = attendanceQueryService.daily(
                enrollment.getId(), today.withDayOfMonth(1), today);

        return ApiResponse.success(new HomeResponse(
                HomeResponse.Profile.of(enrollment, className),
                new HomeResponse.Metrics(
                        studyMinutes(week),
                        studyMinutes(month),
                        attendanceRate(month),
                        confirmedDays(month)),
                routineService.today(enrollment.getId(), today).stream()
                        .map(HomeResponse.Routine::from).toList(),
                noticeService.banners(enrollment.getId()).stream()
                        .map(HomeResponse.Banner::from).toList()));
    }

    /**
     * 순공시간 합계(분).
     *
     * <p><b>확정 전인 날은 빠진다</b> — 순공은 다음날 새벽 배치가 계산한다.
     * 오늘 값을 0으로 채우면 "오늘 하나도 안 했다"로 읽혀 어제까지의 합보다 작아 보인다.
     */
    private int studyMinutes(List<AttendanceQueryService.DailySummary> days) {
        return days.stream()
                .map(AttendanceQueryService.DailySummary::studyMinutes)
                .filter(java.util.Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    /**
     * 출석률(%).
     *
     * <p><b>확정된 날만 분모에 넣는다.</b> 아직 확정 안 된 오늘까지 세면 매일 아침
     * 출석률이 떨어졌다가 다음날 새벽에 회복되는 것처럼 보인다.
     *
     * <p>지각·조퇴는 <b>출석으로 센다</b> — 결석만 결석이다. 이 둘까지 빼면 출석률이
     * 사실상 "무지각률"이 되는데, 화면 이름과 다른 값이 나온다.
     *
     * <p>확정된 날이 하나도 없으면(학기 첫날 등) {@code null}이다 — 0%로 내리면
     * 아무 일도 없었는데 결석한 것처럼 보인다.
     */
    private Integer attendanceRate(List<AttendanceQueryService.DailySummary> days) {
        List<DailyStatus> confirmed = days.stream()
                .map(AttendanceQueryService.DailySummary::finalStatus)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (confirmed.isEmpty()) {
            return null;
        }
        long present = confirmed.stream().filter(s -> s != DailyStatus.ABSENT).count();
        return (int) Math.round(present * 100.0 / confirmed.size());
    }

    private int confirmedDays(List<AttendanceQueryService.DailySummary> days) {
        return (int) days.stream()
                .map(AttendanceQueryService.DailySummary::finalStatus)
                .filter(java.util.Objects::nonNull)
                .count();
    }
}
