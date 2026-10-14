package com.dlab.api.admin.report;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.report.service.DailyReportBoardService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Daily Report 집계 (F-4.11-6) — 관리자 웹 목록.
 *
 * <p><b>조회 전용이다.</b> 새로 쌓는 것이 없다 — 출결 확정 배치가 넣어둔 순공시간,
 * 태깅 원장, 루틴 검수 결과를 학생별로 묶어 줄 세운다. 여기서 값을 다시 만들면
 * <b>같은 숫자가 화면마다 갈린다</b>.
 */
@Tag(name = "관리자 · Daily Report (F-4.11-6)")
@RestController
@RequestMapping("/api/v1/admin/daily-reports")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminDailyReportController {

    private final DailyReportBoardService boardService;

    /**
     * 지점·기간의 학생별 집계 + 순위.
     *
     * <p><b>기존 두 API로는 이 화면을 그릴 수 없다.</b> {@code /statistics}는 지점 전체
     * 개요이고 학생별 통계는 한 명씩만 준다 — 전교생을 줄 세우려면 학생 수만큼 호출해야
     * 하고, 200명짜리 지점이면 쿼리가 200번이다. 그래서 목록을 서버가 한 번에 만든다.
     *
     * <p><b>순위는 서버가 센다.</b> 화면이 세면 페이징된 그 페이지 안에서만 등수가 매겨져
     * 2페이지 첫 줄이 또 1등이 된다. 동점은 같은 등수이고 다음은 건너뛴다(1, 1, 3) —
     * 앱 순공 랭킹과 같은 규칙이라 두 화면의 등수가 갈리지 않는다.
     *
     * <p>⚠️ <b>하원 태깅이 없는 날은 집계하지 않는다</b>(2026-10-13 확정 — 직원이 하원
     * 시각을 수기 입력한다). 그 날 수는 {@code missingCheckOutDays}로 따로 내려가고
     * 순공·재실 합계에는 들어가지 않는다. 0으로 채우면 <b>진짜 0분과 구분되지 않아</b>
     * 담당자가 그 학생을 「공부 안 한 학생」으로 읽는다.
     *
     * <p>⚠️ <b>{@code concentration}(집중도)은 항상 비어 있다 — 정의 미확정</b>이다.
     * 산식이 요구사항에 없어 서버가 만들지 않는다.
     *
     * <p>기본 정렬은 순공시간 많은 순이고 {@code sort}로 바꿀 수 있다. 허용되는 키는
     * {@code studentNo · studentName · className · studyMinutes · presenceMinutes ·
     * routineCompletionRate · missingCheckOutDays}이고 그 밖의 값은 무시된다.
     *
     * @param academyId 지점. 전 지점 권한자만 지정할 수 있고 지점 관리자는 자기 지점으로
     *                  강제된다
     * @param from      조회 시작일
     * @param to        조회 종료일. 최대 31일이다 — 태깅 원장을 기간 통째로 읽는다
     * @param classId   반 필터. 비우면 지점 전체(반 미배정 포함). <b>담임은 이 값과 무관하게
     *                  맡은 학생만 본다</b>
     */
    @GetMapping
    public ApiResponse<List<AdminDailyReportResponse.DailyReportBoardRow>> board(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long classId,
            @PageableDefault(size = 50) Pageable pageable) {

        return ApiResponse.from(boardService.board(me, academyId, from, to, classId, pageable)
                .map(AdminDailyReportResponse.DailyReportBoardRow::from));
    }
}
