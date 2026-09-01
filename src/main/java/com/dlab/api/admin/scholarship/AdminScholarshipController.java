package com.dlab.api.admin.scholarship;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.scholarship.entity.*;
import com.dlab.domain.scholarship.service.ScholarshipReviewService;
import com.dlab.domain.scholarship.service.ScholarshipRuleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * 장학 취소 기준·검토 (0820 규정 · 0826 답변서).
 *
 * <h2>★ 자동으로 취소하지 않는다</h2>
 * 판정은 자동이고 <b>확정은 사람</b>이다. 답변서가 <i>"개인사정에 의해 응시를 못할 경우
 * 더프모 성적으로 대체하는 경우도 있다"</i>고 명시했다 — 자동 확정하면
 * <b>예외인 학생 장학금이 조용히 날아간다.</b>
 *
 * <h2>★ 화면이 안내해야 하는 순서</h2>
 * 기준을 만들어도 <b>켜기 전엔 아무 일도 안 일어난다.</b> 그리고 켠 뒤에도
 * <b>판정을 실행해야</b> 검토 목록이 채워진다. 이 순서를 화면에서 보여주지 않으면
 * "기준을 넣었는데 왜 아무도 안 걸리지"로 헤맨다.
 *
 * <h2>★ 대안(OR)은 한 묶음으로 보여줄 것</h2>
 * <i>"(국+수+탐) 또는 (국+수+영)"</i>이 <b>두 행</b>으로 들어간다
 * ({@code alternativeGroup}이 다르다). 목록에서 따로 보이면 담당자가 대안을 하나만
 * 지우고 규정이 조용히 바뀐다.
 */
@Tag(name = "관리자 · 장학 취소 기준·검토 (0820)")
@RestController
@RequestMapping("/api/v1/admin/scholarship")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN')")
public class AdminScholarshipController {

    private final ScholarshipRuleService ruleService;
    private final ScholarshipReviewService reviewService;

    // ── 기준 ──────────────────────────────────────────────────

    /**
     * 취소 기준 목록. 꺼진 것까지 전부 내려간다.
     *
     * @param academyId 비우면 <b>전 지점 공통 기준</b>이다 — 지점 것과 다른 목록이니
     *                  화면에서 섞어 보여주지 말 것. 공통은 본사만 관리한다
     */
    @GetMapping("/rules")
    public ApiResponse<List<ScholarshipRuleRow>> rules(@CurrentAccount AuthPrincipal me,
                                            @RequestParam(required = false) Long academyId,
                                            @RequestParam short year) {
        return ApiResponse.success(ruleService.findAll(me, academyId, year)
                .stream().map(ScholarshipRuleRow::from).toList());
    }

    /** 기준 생성. <b>항상 꺼진 채로 만들어진다</b> — 검증 전 기준이 돌면 안 된다. */
    @PostMapping("/rules")
    public ApiResponse<ScholarshipRuleRow> createRule(@CurrentAccount AuthPrincipal me,
                                           @Valid @RequestBody ScholarshipRuleRequest request) {
        return ApiResponse.success(ScholarshipRuleRow.from(ruleService.create(
                me, request.academyId(), request.year(), request.ruleType(),
                request.threshold(), request.subjectCodes(), request.scholarshipType(),
                request.alternativeGroupOrDefault(), request.examCodes(),
                request.electiveMode(), request.extraSubjectCode(), request.extraMaxGrade())));
    }

    /**
     * 기준 수정.
     *
     * <p>⚠️ <b>이미 올라온 검토 대상은 안 바뀐다</b> — 판정 당시 임계값을 복사해 두기
     * 때문이다. 기준을 고쳐도 "그때 왜 걸렸는지"는 남는다.
     */
    @PutMapping("/rules/{ruleId}")
    public ApiResponse<ScholarshipRuleRow> updateRule(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long ruleId,
                                           @Valid @RequestBody ScholarshipRuleRequest request) {
        return ApiResponse.success(ScholarshipRuleRow.from(ruleService.update(
                me, ruleId, request.threshold(), request.subjectCodes(),
                request.scholarshipType(), request.alternativeGroupOrDefault(),
                request.examCodes(), request.electiveMode(),
                request.extraSubjectCode(), request.extraMaxGrade())));
    }

    /** 켜고 끄기. 끄면 판정에서 빠지고, <b>이미 올라온 검토 대상은 남는다</b>. */
    @PatchMapping("/rules/{ruleId}/active")
    public ApiResponse<ScholarshipRuleRow> toggleRule(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long ruleId,
                                           @RequestParam boolean active) {
        return ApiResponse.success(ScholarshipRuleRow.from(ruleService.toggleActive(me, ruleId, active)));
    }

    /** 삭제(soft). 과거 검토가 어떤 기준으로 걸렸는지 추적할 수 있어야 한다. */
    @DeleteMapping("/rules/{ruleId}")
    public ApiResponse<Void> deleteRule(@CurrentAccount AuthPrincipal me,
                                        @PathVariable Long ruleId) {
        ruleService.delete(me, ruleId);
        return ApiResponse.empty();
    }

    // ── 판정·검토 ─────────────────────────────────────────────

    /**
     * 판정 실행 — 지점 전체를 훑어 <b>검토 대상</b>을 올린다.
     *
     * <p><b>취소가 아니다.</b> 그리고 <b>장학이 없는 학생은 대상이 아니다</b> —
     * 취소할 것이 없고, 어느 장학인지 모르면 어떤 기준을 적용할지도 정해지지 않는다.
     *
     * <p>여러 번 눌러도 같은 건이 중복으로 쌓이지 않는다.
     *
     * @return 이번에 <b>새로</b> 올라온 건. 0건이면 규칙이 꺼져 있을 수 있다
     */
    @PostMapping("/reviews/judge")
    public ApiResponse<List<ReviewRow>> judge(@CurrentAccount AuthPrincipal me,
                                              @RequestParam Long academyId,
                                              @RequestParam short year) {
        return ApiResponse.success(reviewService.judge(me, academyId, year)
                .stream().map(ReviewRow::from).toList());
    }

    /**
     * 검토 목록.
     *
     * @param status 비우면 전부. 처리할 것만 보려면 {@code PENDING}
     */
    @GetMapping("/reviews")
    public ApiResponse<List<ReviewRow>> reviews(@CurrentAccount AuthPrincipal me,
                                                @RequestParam Long academyId,
                                                @RequestParam short year,
                                                @RequestParam(required = false)
                                                ReviewStatus status) {
        return ApiResponse.success(reviewService.findReviews(me, academyId, year, status)
                .stream().map(ReviewRow::from).toList());
    }

    /**
     * 취소 확정.
     *
     * <p>⚠️ <b>여기서 장학금을 되받지는 않는다.</b> 그건 퇴원 정산에서 하고, 데스크가
     * "정상가 재결제 / 재결제 없이"를 고른다(0820 규정). 취소와 정산은 시점이 다르다.
     */
    @PostMapping("/reviews/{reviewId}/cancel")
    public ApiResponse<ReviewRow> cancel(@CurrentAccount AuthPrincipal me,
                                         @PathVariable Long reviewId,
                                         @RequestBody(required = false) DecisionRequest request) {
        return ApiResponse.success(ReviewRow.from(reviewService.cancel(
                me, reviewId, request == null ? null : request.note())));
    }

    /** 예외 인정. <b>사유가 필수다</b> — 없으면 나중에 "왜 살려뒀나"에 답할 수 없다. */
    @PostMapping("/reviews/{reviewId}/except")
    public ApiResponse<ReviewRow> except(@CurrentAccount AuthPrincipal me,
                                         @PathVariable Long reviewId,
                                         @Valid @RequestBody DecisionRequest request) {
        return ApiResponse.success(ReviewRow.from(
                reviewService.except(me, reviewId, request.note())));
    }

    // ── DTO ───────────────────────────────────────────────────

    /**
     * @param academyId        비우면 <b>전 지점 공통</b>. 본사만 만들 수 있다
     * @param scholarshipType  적용 장학. 비우면 장학 종류와 무관(벌점이 그렇다).
     *                         ⚠️ {@code scholarship.scholarship_type}과 <b>글자가 같아야</b>
     *                         한다 — 다르면 규칙이 아무에게도 안 걸린다
     * @param alternativeGroup 같은 (장학, 요건) 안의 OR 대안 번호. 비우면 1
     * @param subjectCodes     등급합에 고정으로 들어가는 과목(콤마). 탐구는 여기 적지 않는다
     * @param examCodes        대상 회차(콤마). 비우면 {@code JUNE,SEPT}
     * @param electiveMode     탐구 집계. 비우면 탐구를 안 본다
     * @param extraSubjectCode AND 조건 과목. {@code extraMaxGrade}와 <b>함께</b> 지정한다
     */
    public record ScholarshipRuleRequest(Long academyId,
                              @NotNull Short year,
                              @NotNull CancelRuleType ruleType,
                              @NotNull Integer threshold,
                              @Size(max = 200) String subjectCodes,
                              @Size(max = 20) String scholarshipType,
                              Short alternativeGroup,
                              @Size(max = 50) String examCodes,
                              ElectiveMode electiveMode,
                              @Size(max = 20) String extraSubjectCode,
                              Short extraMaxGrade) {

        short alternativeGroupOrDefault() {
            return alternativeGroup == null ? 1 : alternativeGroup;
        }
    }

    /** @param note 예외 인정은 필수, 취소 확정은 선택 */
    public record DecisionRequest(@NotNull @Size(max = 500) String note) {
    }

    /** @param active {@code false}면 만들어만 두고 안 도는 기준이다 */
    public record ScholarshipRuleRow(Long id, Long academyId, short year, CancelRuleType ruleType,
                          String scholarshipType, short alternativeGroup, int threshold,
                          String subjectCodes, String examCodes, ElectiveMode electiveMode,
                          String extraSubjectCode, Short extraMaxGrade, boolean active) {

        static ScholarshipRuleRow from(ScholarshipCancelRule r) {
            return new ScholarshipRuleRow(r.getId(),
                    r.isCommon() ? null : r.getAcademy().getId(),
                    r.getYear(), r.getRuleType(), r.getScholarshipType(),
                    r.getAlternativeGroup(), r.getThreshold(), r.getSubjectCodes(),
                    r.getExamCodes(), r.getElectiveMode(), r.getExtraSubjectCode(),
                    r.getExtraMaxGrade(), r.isActive());
        }
    }

    /**
     * @param detectedValue ⚠️ 등급합은 <b>2배 스케일</b>이다 — 탐구 2과목 평균이 3.5처럼
     *                      정수가 아닐 수 있어서다. 화면에 그대로 띄우지 말고
     *                      {@code detail}을 보여줄 것
     * @param detail        사람이 읽는 판정 근거. 대안이 여럿이면 전부 들어 있다
     */
    public record ReviewRow(Long id, Long enrollmentId, String studentNo, String studentName,
                            CancelRuleType ruleType, int detectedValue, int threshold,
                            String detail, ReviewStatus status, String decisionNote,
                            Long decidedBy, Instant decidedAt) {

        static ReviewRow from(ScholarshipReview r) {
            var enrollment = r.getEnrollment();
            return new ReviewRow(r.getId(), enrollment.getId(),
                    enrollment.getStudentNo(), enrollment.getStudent().getName(),
                    r.getRuleType(), r.getDetectedValue(), r.getThreshold(), r.getDetail(),
                    r.getStatus(), r.getDecisionNote(), r.getDecidedBy(), r.getDecidedAt());
        }
    }
}
