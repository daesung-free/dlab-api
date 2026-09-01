package com.dlab.api.admin.consult;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.consult.entity.ConsultLog;
import com.dlab.domain.consult.entity.ConsultMethod;
import com.dlab.domain.consult.entity.ConsultTag;
import com.dlab.domain.consult.entity.ConsultType;
import com.dlab.domain.consult.service.ConsultService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 상담 (F-4.11-4) — <b>일지까지다</b>.
 *
 * <p>구글시트로 운영하던 것을 시스템화한다.
 *
 * <h2>TODO — 리포트는 다른 도메인 대기</h2>
 * <ul>
 *   <li><b>과목별 이행률(별점)</b> — 학습계획(F-4.11-2). 표기 정합도 미확정(I-19:
 *       학습계획은 O/X로 확정됐는데 상담은 별점이다)</li>
 *   <li><b>성적 상세 결합</b> — 성적(F-4.6-1). E-2·I-11·S-2 대기</li>
 *   <li><b>신상기록부 작성 여부</b> — F-4.11-8. I-17 대기.
 *       현황 응답의 {@code profileWritten}이 지금 항상 {@code null}이다</li>
 *   <li><b>담임 스티커·학부모 공유</b> — 리포트 발송이라 위 셋에 딸린다</li>
 * </ul>
 */
@Tag(name = "관리자 · 상담 일지 (F-4.11-4)")
@RestController
@RequestMapping("/api/v1/admin/consults")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER')")
public class AdminConsultController {

    private final ConsultService consultService;

    // ── 일지 ──────────────────────────────────────────────────

    /**
     * 기간별 상담 목록. {@code teacherId}로 담임별 필터.
     *
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @GetMapping
    public ApiResponse<List<LogResponse>> list(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long teacherId) {
        return ApiResponse.success(consultService.findByPeriod(me, academyId, from, to, teacherId).stream()
                .map(LogResponse::from).toList());
    }

    /** 학생 상담 이력 — 최근 순. */
    @GetMapping("/students/{enrollmentId}")
    public ApiResponse<List<LogResponse>> byStudent(@CurrentAccount AuthPrincipal me,
                                                    @PathVariable Long enrollmentId) {
        return ApiResponse.success(consultService.findByStudent(me, enrollmentId).stream()
                .map(LogResponse::from).toList());
    }

    /** 상담 일지 작성. */
    @PostMapping
    public ApiResponse<LogResponse> write(@CurrentAccount AuthPrincipal me,
                                          @Valid @RequestBody WriteRequest request) {
        return ApiResponse.success(LogResponse.from(consultService.write(
                me, request.enrollmentId(), request.teacherId(), request.consultType(),
                request.methodOrDefault(), request.consultedAt(), request.placeNote(),
                request.content(), request.actionPlan(), request.nextDueDate(),
                request.tagIds())));
    }

    /** 상담 일지 수정. */
    @PutMapping("/{logId}")
    public ApiResponse<LogResponse> update(@CurrentAccount AuthPrincipal me,
                                           @PathVariable Long logId,
                                           @Valid @RequestBody UpdateRequest request) {
        return ApiResponse.success(LogResponse.from(consultService.update(
                me, logId, request.consultType(), request.methodOrDefault(),
                request.consultedAt(), request.placeNote(), request.content(),
                request.actionPlan(), request.actionDone(), request.nextDueDate(),
                request.tagIds())));
    }

    /** 삭제(soft). 상담 이력은 다음 상담의 근거라 물리 삭제하지 않는다. */
    @DeleteMapping("/{logId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me, @PathVariable Long logId) {
        consultService.delete(me, logId);
        return ApiResponse.empty();
    }

    // ── 현황 ──────────────────────────────────────────────────

    /**
     * 상담 현황 — <b>재원생 전원</b>이 나온다.
     *
     * <p>일지만 주면 미상담자가 목록에서 사라져 "누구를 아직 안 만났나"를 알 수 없다.
     */
    @GetMapping("/status")
    public ApiResponse<List<ConsultService.ConsultStatusRow>> status(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam(required = false) Long teacherId) {
        return ApiResponse.success(consultService.status(me, academyId, teacherId));
    }

    // ── 태그 마스터 ────────────────────────────────────────────

    /**
     * 상담 태그 목록.
     *
     * @param academyId 조회할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @GetMapping("/tags")
    public ApiResponse<List<TagResponse>> tags(@CurrentAccount AuthPrincipal me,
                                               @RequestParam(required = false) Long academyId,
                                               @RequestParam short year,
                                               @RequestParam(defaultValue = "false")
                                               boolean includeInactive) {
        return ApiResponse.success(
                consultService.tags(me, academyId, year, includeInactive).stream()
                .map(TagResponse::from).toList());
    }

    /**
     * 상담 태그 등록.
     *
     * @param academyId 등록할 지점. <b>비우면 내 지점</b>이다.
     *                  전 지점 권한자(본사)는 지정해야 한다
     */
    @PostMapping("/tags")
    public ApiResponse<TagResponse> createTag(@CurrentAccount AuthPrincipal me,
                                              @RequestParam(required = false) Long academyId,
                                              @Valid @RequestBody TagRequest request) {
        return ApiResponse.success(TagResponse.from(consultService.createTag(
                me, academyId, request.year(), request.consultType(), request.name(),
                request.sortOrderOrZero())));
    }

    /** 상담 태그 수정. 이미 붙은 일지의 태그도 같이 바뀐다. */
    @PutMapping("/tags/{tagId}")
    public ApiResponse<TagResponse> updateTag(@CurrentAccount AuthPrincipal me,
                                              @PathVariable Long tagId,
                                              @Valid @RequestBody TagUpdateRequest request) {
        return ApiResponse.success(TagResponse.from(consultService.updateTag(
                me, tagId, request.name(), request.consultType(), request.sortOrderOrZero(),
                request.maxDisplayOrDefault(), request.active())));
    }

    // ── DTO ──────────────────────────────────────────────────

    public record WriteRequest(
            @NotNull(message = "학생은 필수입니다.") Long enrollmentId,
            /** 미지정이면 그 학생의 담임이 상담자가 된다. */
            Long teacherId,
            @NotNull(message = "상담 유형은 필수입니다.") ConsultType consultType,
            ConsultMethod method,
            @NotNull(message = "상담일은 필수입니다.")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate consultedAt,
            @Size(max = 100) String placeNote,
            @NotBlank(message = "상담 내용은 필수입니다.") String content,
            String actionPlan,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nextDueDate,
            List<Long> tagIds) {

        ConsultMethod methodOrDefault() {
            return method == null ? ConsultMethod.FACE : method;
        }
    }

    public record UpdateRequest(
            @NotNull ConsultType consultType,
            ConsultMethod method,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate consultedAt,
            @Size(max = 100) String placeNote,
            @NotBlank String content,
            String actionPlan,
            boolean actionDone,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate nextDueDate,
            List<Long> tagIds) {

        ConsultMethod methodOrDefault() {
            return method == null ? ConsultMethod.FACE : method;
        }
    }

    public record LogResponse(Long id, Long enrollmentId, String studentNo, String studentName,
                              String teacherName, ConsultType consultType, ConsultMethod method,
                              LocalDate consultedAt, String placeNote, String content,
                              String actionPlan, boolean actionDone, LocalDate nextDueDate,
                              List<String> tags) {

        static LogResponse from(ConsultLog l) {
            return new LogResponse(l.getId(), l.getEnrollment().getId(),
                    l.getEnrollment().getStudentNo(),
                    l.getEnrollment().getStudent().getName(),
                    l.getTeacher() == null ? null : l.getTeacher().getName(),
                    l.getConsultType(), l.getMethod(), l.getConsultedAt(),
                    l.getPlaceNote(), l.getContent(), l.getActionPlan(), l.isActionDone(),
                    l.getNextDueDate(),
                    l.tagList().stream().map(ConsultTag::getName).toList());
        }
    }

    public record TagRequest(
            @NotNull short year,
            /** {@code null}이면 모든 유형에서 쓴다. */
            ConsultType consultType,
            @NotBlank(message = "태그명은 필수입니다.") @Size(max = 50) String name,
            Short sortOrder) {

        short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }
    }

    public record TagUpdateRequest(
            ConsultType consultType,
            @NotBlank @Size(max = 50) String name,
            Short sortOrder,
            Short maxDisplay,
            boolean active) {

        short sortOrderOrZero() {
            return sortOrder == null ? 0 : sortOrder;
        }

        short maxDisplayOrDefault() {
            return maxDisplay == null ? 10 : maxDisplay;
        }
    }

    /** @param maxDisplay 화면 노출 상한. 태그가 쌓이면 담임이 못 찾는다 */
    public record TagResponse(Long id, ConsultType consultType, String name,
                              short sortOrder, short maxDisplay, boolean active) {
        static TagResponse from(ConsultTag t) {
            return new TagResponse(t.getId(), t.getConsultType(), t.getName(),
                    t.getSortOrder(), t.getMaxDisplay(), t.isActive());
        }
    }
}
