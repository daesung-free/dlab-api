package com.dlab.api.admin.routine;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.routine.service.DailyRoutineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 데일리 루틴 관리 (F-4.11-1).
 *
 * <p><b>결과 입력은 반 단위 그리드뿐이다.</b> 시트가 *"반 단위 그리드 일괄 입력 필수
 * (개별 폼 금지)"*를 요구해서, 한 건씩 저장하는 엔드포인트를 두지 않았다.
 */
@Tag(name = "관리자 · 데일리 루틴 (F-4.11-1)")
@RestController
@RequestMapping("/api/v1/admin/routines")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
public class AdminRoutineController {

    private final DailyRoutineService routineService;

    // ── 세팅 ─────────────────────────────────────────────────────

    @GetMapping
    public ApiResponse<List<RoutineResponse.Detail>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Long academyId,
            @RequestParam Integer year,
            @RequestParam Integer month) {
        return ApiResponse.success(routineService
                .findByMonth(academyId, year.shortValue(), month.shortValue(), me).stream()
                .map(RoutineResponse.Detail::from).toList());
    }

    @PostMapping
    public ApiResponse<RoutineResponse.Detail> create(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody RoutineRequests.Create request) {
        return ApiResponse.success(RoutineResponse.Detail.from(routineService.create(
                request.academyId(), request.year().shortValue(), request.month().shortValue(),
                request.classId(), request.name(), request.subject(),
                request.maxScore() == null ? 0 : request.maxScore().shortValue(),
                request.recommended() != null && request.recommended(),
                request.sortOrder() == null ? 0 : request.sortOrder().shortValue(), me)));
    }

    @PatchMapping("/{routineId}")
    public ApiResponse<RoutineResponse.Detail> update(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long routineId,
            @Valid @RequestBody RoutineRequests.Update request) {
        return ApiResponse.success(RoutineResponse.Detail.from(routineService.update(
                routineId, request.name(), request.subject(),
                request.maxScore() == null ? null : request.maxScore().shortValue(),
                request.recommended(),
                request.sortOrder() == null ? null : request.sortOrder().shortValue(), me)));
    }

    @DeleteMapping("/{routineId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long routineId) {
        routineService.delete(routineId, me);
        return ApiResponse.empty();
    }

    /**
     * 전월 복사.
     *
     * <p>대상 월에 이미 세팅이 있으면 <b>거부한다</b> — 덮어쓰면 손으로 고친 것이
     * 통째로 사라지고 되돌릴 수 없다(전년도 복사와 같은 원칙).
     */
    @PostMapping("/copy-from-previous-month")
    public ApiResponse<Map<String, Integer>> copyFromPreviousMonth(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody RoutineRequests.CopyFromPreviousMonth request) {
        int copied = routineService.copyFromPreviousMonth(
                request.academyId(), request.year().shortValue(), request.month().shortValue(), me);
        return ApiResponse.success(Map.of("copied", copied));
    }

    // ── 결과 (반 단위 그리드) ────────────────────────────────────

    /**
     * 그리드 조회.
     *
     * <p><b>아직 입력 안 한 학생도 빈 줄로 나온다</b>({@code id}가 {@code null}).
     * 행이 있는 학생만 보이면 초기 상태에서 그리드가 텅 비어 입력을 시작할 수 없다.
     */
    @GetMapping("/{routineId}/results")
    public ApiResponse<List<RoutineResponse.GridRow>> grid(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long routineId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(routineService.grid(routineId, date, me).stream()
                .map(RoutineResponse.GridRow::from).toList());
    }

    /** 일괄 입력. 가채점과 검수 점수를 각각 다른 칸에 넣는다. */
    @PutMapping("/{routineId}/results")
    public ApiResponse<Map<String, Integer>> saveResults(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long routineId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @Valid @RequestBody RoutineRequests.SaveResults request) {
        int saved = routineService.saveResults(routineId, date,
                request.results().stream()
                        .map(r -> new DailyRoutineService.ResultInput(
                                r.enrollmentId(), r.status(),
                                r.selfScore() == null ? null : r.selfScore().shortValue(),
                                r.reviewedScore() == null ? null : r.reviewedScore().shortValue(),
                                r.memo()))
                        .toList(),
                me);
        return ApiResponse.success(Map.of("saved", saved));
    }

    /**
     * 일괄 공개.
     *
     * <p>검수까지 끝난 것만 연다 — 반 전체를 채점한 뒤 한 번에 열어야 하고,
     * 중간에 노출되면 "누구는 나왔는데 나는 왜 없냐"가 된다.
     */
    @PostMapping("/{routineId}/results/publish")
    public ApiResponse<Map<String, Integer>> publish(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long routineId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(Map.of("published", routineService.publishAll(routineId, date, me)));
    }
}
