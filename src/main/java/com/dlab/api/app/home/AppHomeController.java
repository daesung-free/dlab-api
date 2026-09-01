package com.dlab.api.app.home;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
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
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "앱 · 홈 (A-3)")
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
     * 홈 화면 한 번에 (A-3).
     *
     * <p>순공시간·출석률·오늘 루틴·공지 배너를 <b>한 번에 내린다.</b> 나눠 부르면 앱이 여러 번
     * 왕복하고 그 사이 값이 어긋난다.
     *
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

        // ★ 집계 규칙은 도메인이 갖는다 — 관리자 대시보드가 같은 값을 봐야 한다.
        //   표현 계층에서 계산하면 화면마다 조금씩 갈리는데, 출석률은 학생·학부모가
        //   직접 보는 숫자라 두 화면이 다른 값을 내면 신뢰가 깨진다
        var weekly = attendanceQueryService.summarize(week);
        var monthly = attendanceQueryService.summarize(month);

        return ApiResponse.success(new HomeResponse(
                HomeResponse.Profile.of(enrollment, className),
                new HomeResponse.Metrics(
                        weekly.studyMinutes(),
                        monthly.studyMinutes(),
                        monthly.attendanceRate(),
                        monthly.confirmedDays()),
                routineService.today(enrollment.getId(), today).stream()
                        .map(HomeResponse.HomeRoutine::from).toList(),
                noticeService.banners(enrollment.getId()).stream()
                        .map(HomeResponse.Banner::from).toList()));
    }



}
