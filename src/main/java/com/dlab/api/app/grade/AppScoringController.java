package com.dlab.api.app.grade;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.grade.service.ScoringQueryService;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.AppScopeResolver;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 채점 탭 (시안 4.5) — 개요 · 복습 우선순위 · 평가요소별 · 단원별.
 *
 * <p>근거는 관리자가 올린 문항 정보(문항분석표·정답률)와 학생 정오·답안이다.
 */
@Tag(name = "앱 · 채점 (시안 4.5)")
@RestController
@RequestMapping("/api/v1/app/grades")
@RequiredArgsConstructor
public class AppScoringController {

    private final AppScopeResolver scopeResolver;
    private final ScoringQueryService scoringQueryService;

    /**
     * 한 회차 채점 — 영역 단위(국어 영역 = 공통 + 선택).
     *
     * <p>복습 우선순위는 <b>틀린 문항을 전국 정답률이 높은 순</b>이다 — 남들은 맞혔는데 나만
     * 틀린 문항이 가장 빨리 올릴 수 있는 점수다. 내가 고른 오답이 가장 많이 고른 오답이면
     * {@code trap=true}.
     *
     * <p>평가요소·단원별은 내 정답률과 전국 정답률(문항 평균)을 함께 준다 — 원래 어려운
     * 유형인지 나만 약한 유형인지 가르려면 둘 다 있어야 한다.
     */
    @GetMapping("/exams/{examMasterId}/scoring")
    public ApiResponse<List<ScoringQueryService.Area>> scoring(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long examMasterId,
            @RequestParam(required = false) Long studentId) {
        StudentEnrollment enrollment = scopeResolver.resolve(me.accountId(), studentId);
        return ApiResponse.success(scoringQueryService.scoring(enrollment, examMasterId));
    }
}
