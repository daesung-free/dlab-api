package com.dlab.api.admin.admission;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.admission.entity.*;
import com.dlab.domain.admission.service.AdmissionResultService;
import io.swagger.v3.oas.annotations.tags.Tag;
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

/**
 * 실적 관리 (F-4.10-6) — 수시/정시 지원대학과 결과.
 *
 * <h2>지원과 실적이 한 화면이다</h2>
 * 지원한 대학 목록에 합불이 붙으면 그게 곧 실적이다. 따로 두면 같은 대학을 두 번 입력하게
 * 된다.
 *
 * <h2>개수 제한은 서버가 지킨다</h2>
 * 수시 6개·정시 3개. 화면에서만 막으면 두 창에서 동시에 넣을 때 통과한다.
 *
 * <h2>대학·학과 마스터는 두지 않는다</h2>
 * 전국 대학 전형은 매년 바뀐다(§4). {@code /suggestions} 가 <b>쌓인 값에서</b> 자동완성
 * 후보를 만든다 — 마스터가 없어도 지금 쓸 수 있고, 쓸수록 정확해진다.
 */
@Tag(name = "관리자 · 실적 관리 (F-4.10-6)")
@RestController
@RequestMapping("/api/v1/admin/admission-results")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminAdmissionResultController {

    private final AdmissionResultService resultService;
    private final com.dlab.domain.admission.service.AdmissionResultImportService importService;

    /**
     * 그해 전체 실적 — 학생을 고르지 않고 지점 전체를 본다. 학번순.
     *
     * @param academyId 비우면 내 지점. 전 지점 권한자는 지정해야 한다
     * @param result    비우면 전체. {@code PENDING}=발표 전
     * @param keyword   학생 이름·학번·대학명·학과명
     */
    @GetMapping
    public ApiResponse<List<ResultView>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam(required = false) AdmissionResultStatus result,
            @RequestParam(required = false) AdmissionType admissionType,
            @RequestParam(required = false) String keyword,
            @org.springframework.data.web.PageableDefault(size = 50)
            org.springframework.data.domain.Pageable pageable) {
        return ApiResponse.from(resultService.search(me, academyId, year, result, admissionType,
                keyword, pageable).map(ResultView::from));
    }

    /**
     * 엑셀 일괄 등록 미리보기 — <b>아무것도 저장하지 않는다.</b>
     *
     * <p>열: 학번(필수) · 구분(수시/정시, 필수) · 대학명(필수) · 학과명(필수) · 이름 · 전형명 ·
     * 결과(합격/불합격/발표전/등록포기, 비우면 발표전) · 메모. 열 순서는 상관없다(헤더명으로 찾는다).
     *
     * <p>이름 칸이 있으면 학번의 학생과 대조한다 — 학번 오타로 다른 학생 실적이 들어가지 않게.
     * 정원 초과·이미 있는 지원(같은 구분·대학·학과)은 오류로 잡혀, 같은 파일을 다시 올려도 중복이 안 쌓인다.
     */
    @PostMapping("/import/preview")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<com.dlab.common.excel.ImportPreview<
            com.dlab.domain.admission.service.AdmissionResultImportService.ParsedResult>> previewImport(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return ApiResponse.success(importService.preview(me, academyId, file.getInputStream()));
    }

    /** 반영 — <b>오류행이 있어도 정상행은 넣는다.</b> 결과는 미리보기와 같은 모양이다. */
    @PostMapping("/import")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
    public ApiResponse<com.dlab.common.excel.ImportPreview<
            com.dlab.domain.admission.service.AdmissionResultImportService.ParsedResult>> importResults(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @org.springframework.web.bind.annotation.RequestPart("file")
            org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        return ApiResponse.success(importService.importResults(me, academyId, file.getInputStream()));
    }

    /** 학생 한 명의 지원 목록. 수시·정시가 함께 온다. */
    @GetMapping("/students/{enrollmentId}")
    public ApiResponse<List<ResultView>> byStudent(@CurrentAccount AuthPrincipal me,
                                                   @PathVariable Long enrollmentId) {
        return ApiResponse.success(resultService.findByStudent(me, enrollmentId)
                .stream().map(ResultView::from).toList());
    }

    /** 등록. 정원(수시 6·정시 3)을 넘으면 거절한다. */
    @PostMapping("/students/{enrollmentId}")
    public ApiResponse<ResultView> create(@CurrentAccount AuthPrincipal me,
                                          @PathVariable Long enrollmentId,
                                          @Valid @RequestBody SaveResult request) {
        return ApiResponse.success(ResultView.from(resultService.create(
                me, enrollmentId, request.admissionType(), request.universityName(),
                request.departmentName(), request.trackName(), request.result(),
                AdmissionSource.STAFF, request.memo())));
    }

    /**
     * 수정. 비워 보낸 항목은 바꾸지 않는다.
     *
     * <p>★ <b>고치면 입력 주체가 직원으로 바뀐다</b> — 학생이 적어낸 값과 확인을 거친 값을
     * 구분하기 위해서다.
     */
    @PatchMapping("/{resultId}")
    public ApiResponse<ResultView> update(@CurrentAccount AuthPrincipal me,
                                          @PathVariable Long resultId,
                                          @RequestBody UpdateResult request) {
        return ApiResponse.success(ResultView.from(resultService.update(
                me, resultId, request.admissionType(), request.universityName(),
                request.departmentName(), request.trackName(), request.result(),
                request.memo())));
    }

    /** 삭제. <b>지우지 않고 내린다</b> — 지난 지원 이력이 실적 집계의 근거다. */
    @DeleteMapping("/{resultId}")
    public ApiResponse<Void> delete(@CurrentAccount AuthPrincipal me,
                                    @PathVariable Long resultId) {
        resultService.delete(me, resultId);
        return ApiResponse.empty();
    }

    /**
     * 기간별 집계.
     *
     * <p>★ <b>합격률의 분모는 발표가 난 건수({@code decided})</b>다. 전체로 나누면 아직
     * 발표 전인 지원까지 실패로 잡혀 실제보다 낮게 나온다.
     *
     * <p>합격에는 <b>등록포기가 포함</b>된다 — 붙은 것은 사실이고, 등록 여부는 결과별
     * 집계({@code byResult})가 따로 답한다.
     */
    @GetMapping("/statistics")
    public ApiResponse<AdmissionResultService.Statistics> statistics(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Long academyId,
            @RequestParam short year,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(resultService.statistics(me, academyId, year, from, to));
    }

    /**
     * 자동완성 후보 — 이미 입력된 값에서 만든다.
     *
     * <p>★ <b>비어 있어도 정상이다.</b> 첫 해에는 쌓인 값이 없고, 그때도 직접 입력으로
     * 쓸 수 있어야 한다. 마스터를 기다리지 않는 이유다.
     *
     * @param university 주면 그 대학의 학과만 추린다
     */
    @GetMapping("/suggestions")
    public ApiResponse<AdmissionResultService.Suggestions> suggestions(
            @RequestParam(required = false) String university,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(resultService.suggest(university, keyword));
    }

    /**
     * @param result 비우면 {@code PENDING}(발표 전)이다 — 불합격과 구분된다
     */
    public record SaveResult(
            @NotNull(message = "수시·정시 구분은 필수입니다.") AdmissionType admissionType,
            @NotBlank(message = "대학명은 필수입니다.") @Size(max = 100) String universityName,
            @NotBlank(message = "학과명은 필수입니다.") @Size(max = 100) String departmentName,
            @Size(max = 100) String trackName,
            AdmissionResultStatus result,
            @Size(max = 500) String memo) {
    }

    public record UpdateResult(AdmissionType admissionType,
                               @Size(max = 100) String universityName,
                               @Size(max = 100) String departmentName,
                               @Size(max = 100) String trackName,
                               AdmissionResultStatus result,
                               @Size(max = 500) String memo) {
    }

    /** @param source 학생이 적어낸 값인지 직원이 확인한 값인지 */
    public record ResultView(Long id, Long enrollmentId, String studentName, String studentNo,
                             AdmissionType admissionType, String universityName,
                             String departmentName, String trackName,
                             AdmissionResultStatus result, AdmissionSource source, String memo) {

        static ResultView from(AdmissionResult r) {
            return new ResultView(r.getId(), r.getEnrollment().getId(),
                    r.getEnrollment().getStudent().getName(), r.getEnrollment().getStudentNo(),
                    r.getAdmissionType(), r.getUniversityName(), r.getDepartmentName(),
                    r.getTrackName(), r.getResult(), r.getSource(), r.getMemo());
        }
    }
}
