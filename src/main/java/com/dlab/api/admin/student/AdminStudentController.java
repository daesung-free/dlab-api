package com.dlab.api.admin.student;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.search.SearchScope;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.*;
import com.dlab.domain.search.entity.SearchType;
import com.dlab.domain.search.service.SavedSearchService;
import com.dlab.domain.user.service.StudentExportService;
import com.dlab.domain.user.service.StudentImportService;
import com.dlab.domain.user.service.StudentStatusService;
import com.dlab.domain.user.service.StudentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * 관리자 웹 — 학생 검색·신규 접수.
 *
 * <p>지점 스코프는 {@link SearchScope}가 인증 주체에서 뽑는다. 요청 파라미터로 받으면
 * 값을 바꿔 보내는 것만으로 다른 지점 학생이 조회된다(CLAUDE.md §7).
 */
@Tag(name = "관리자 · 학생 관리 (F-4.1)")
@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminStudentController {

    private final StudentService studentService;
    private final StudentImportService studentImportService;
    private final StudentExportService studentExportService;
    private final SavedSearchService savedSearchService;
    private final StudentStatusService studentStatusService;

    /**
     * 학생 검색. 조건이 12개라 {@code SearchPredicates}로 조합한다 — 값이 없으면 그 조건이 빠진다.
     *
     * <p><b>정렬</b>은 {@code ?sort=필드,asc|desc}로 보낸다(여러 개 가능:
     * {@code ?sort=grade,asc&sort=name,asc}). 허용 필드는 다음뿐이고,
     * <b>목록에 없는 필드는 오류가 아니라 무시</b>된다(임의 컬럼 정렬로 인덱스를 못 타는 것을 막는다).
     *
     * <ul>
     *   <li>{@code studentNo} — 학번</li>
     *   <li>{@code name} — 이름 ({@code student.name}으로 보내도 된다)</li>
     *   <li>{@code grade} — 학년</li>
     *   <li>{@code track} — 계열</li>
     *   <li>{@code enrollmentStatus} — 재원 상태</li>
     *   <li>{@code admissionDate} — 입학일</li>
     * </ul>
     *
     * <p>정렬을 보내지 않으면 <b>학번 오름차순</b>이고, 어떤 정렬을 보내든 마지막에 학번이
     * tie-breaker로 붙는다 — 동점 구간의 순서가 매 요청마다 달라지면 페이징에서 학생이
     * 중복되거나 누락된다. 반(class)으로는 정렬할 수 없다(배정이 별도 테이블이다).
     */
    @GetMapping
    public ApiResponse<Page<StudentResponse>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) GradeType grade,
            @RequestParam(required = false) TrackType track,
            @RequestParam(required = false) EnrollmentStatus status,
            @RequestParam(required = false) Long classId,
            @PageableDefault(size = 20) Pageable pageable) {

        return ApiResponse.success(studentService
                .search(SearchScope.of(me, year), keyword, grade, track, status, classId, pageable)
                .map(StudentResponse::from));
    }

    /** 학생 상세. */
    @GetMapping("/{enrollmentId}")
    public ApiResponse<StudentResponse> get(@CurrentAccount AuthPrincipal me,
                                            @PathVariable Long enrollmentId) {
        return ApiResponse.success(StudentResponse.from(studentService.get(enrollmentId, me)));
    }

    /** 신규 접수. 학번은 서버가 채번한다. */
    @PostMapping
    public ApiResponse<StudentResponse> admit(@CurrentAccount AuthPrincipal me,
                                              @Valid @RequestBody StudentRequests.Admit request) {
        return ApiResponse.success(StudentResponse.from(studentService.admit(
                request.academyId(), request.year().shortValue(), request.name(),
                request.phone(), request.grade(), request.track(), me)));
    }

    /** 학생 정보 수정. 보내지 않은 필드는 그대로 둔다 — 부분 수정이라 {@code PATCH}다. */
    @PatchMapping("/{enrollmentId}")
    public ApiResponse<StudentResponse> update(@CurrentAccount AuthPrincipal me,
                                               @PathVariable Long enrollmentId,
                                               @Valid @RequestBody StudentRequests.Update request) {
        return ApiResponse.success(StudentResponse.from(studentService.update(
                enrollmentId, request.name(), request.phone(), request.birthDate(),
                request.gender(), request.schoolName(), request.address(), request.grade(),
                request.track(), request.status(), me)));
    }

    // ── 상태 관리 (F-4.1-8) ──

    /**
     * 재적 상태 전이. 상태만 바꾸는 게 아니라 <b>후속처리까지 한 트랜잭션</b>에 묶는다 —
     * 퇴원·제적·수료면 반·좌석·사물함 배정을 비우고 앱 계정을 막고, 도메인별 정리
     * (급식 신청 취소·미납 확인)가 이어서 돈다.
     *
     * <p>휴원은 정리하지 않는다. 돌아올 학생의 자리를 비우면 복귀 때 잃는다.
     *
     * <p><b>{@code followUps}를 함께 내린다.</b> 처리한 사람이 그 자리에서 봐야
     * "미납이 남았다"를 안다 — 로그로만 남기면 화면을 닫는 순간 아무도 모른다.
     */
    @PostMapping("/{enrollmentId}/status")
    public ApiResponse<StatusChangeResponse> changeStatus(
            @CurrentAccount AuthPrincipal me, @PathVariable Long enrollmentId,
            @Valid @RequestBody StudentRequests.ChangeStatus request) {
        var result = studentStatusService.changeStatus(
                enrollmentId, request.status(), request.reason(), me);
        return ApiResponse.success(StatusChangeResponse.from(result));
    }

    /**
     * 상태 전이 결과.
     *
     * @param followUps 도메인별 후속처리 결과. {@code blocking}이 붙은 건
     *                  <b>사람이 이어서 처리해야 하는 것</b>이라 화면이 눈에 띄게 표시한다
     */
    public record StatusChangeResponse(StudentResponse student, List<FollowUpNote> followUps) {

        static StatusChangeResponse from(
                com.dlab.domain.user.service.StudentStatusService.StatusChangeResult result) {
            return new StatusChangeResponse(
                    StudentResponse.from(result.enrollment()),
                    result.followUps().stream()
                            .map(n -> new FollowUpNote(n.area(), n.message(), n.blocking()))
                            .toList());
        }
    }

    public record FollowUpNote(String area, String message, boolean blocking) {
    }

    /** 상태 변경 이력. "퇴원 처리가 언제 누구에 의해 됐나"에 답하는 화면용. */
    @GetMapping("/{enrollmentId}/status-logs")
    public ApiResponse<List<StatusLogResponse>> statusLogs(@CurrentAccount AuthPrincipal me,
                                                           @PathVariable Long enrollmentId) {
        return ApiResponse.success(studentStatusService.history(enrollmentId, me).stream()
                .map(StatusLogResponse::from).toList());
    }

    // ── 엑셀 Export ──

    /**
     * 명단 다운로드. <b>마스킹 기본 ON</b>(실행가이드 3.2) — 연락처는 {@code 010-****-1234}로 나간다.
     *
     * <p>검색 조건을 그대로 받는다. 화면에서 걸러 본 목록을 그대로 내려받는 게 자연스럽고,
     * 전체만 받게 하면 사용자가 엑셀에서 다시 거르게 된다.
     *
     * <p>업로드와 <b>같은 헤더</b>라 내려받아 고친 뒤 그대로 다시 올릴 수 있다.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) GradeType grade,
            @RequestParam(required = false) TrackType track,
            @RequestParam(required = false) EnrollmentStatus status,
            @RequestParam(required = false) Long classId) {

        byte[] file = studentExportService.export(
                SearchScope.of(me, year), keyword, grade, track, status, classId);

        String filename = URLEncoder.encode("학생명단.xlsx", StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + filename)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file);
    }

    // ── 검색조건 저장 ──

    /** 본인이 저장한 조건만 나온다 — 개인 설정이다. */
    @GetMapping("/saved-searches")
    public ApiResponse<List<SavedSearchResponse>> savedSearches(@CurrentAccount AuthPrincipal me) {
        return ApiResponse.success(savedSearchService.list(SearchType.STUDENT, me).stream()
                .map(SavedSearchResponse::from).toList());
    }

    /** 같은 이름이 있으면 덮어쓴다 — 이름이 같은데 조건이 다른 항목이 둘이면 어느 게 최신인지 모른다. */
    @PostMapping("/saved-searches")
    public ApiResponse<SavedSearchResponse> saveSearch(
            @CurrentAccount AuthPrincipal me,
            @Valid @RequestBody StudentRequests.SaveSearch request) {
        return ApiResponse.success(SavedSearchResponse.from(savedSearchService.save(
                SearchType.STUDENT, request.name(), request.conditions(), me)));
    }

    /** 저장한 검색 삭제. */
    @DeleteMapping("/saved-searches/{id}")
    public ApiResponse<Void> deleteSavedSearch(@CurrentAccount AuthPrincipal me,
                                               @PathVariable Long id) {
        savedSearchService.delete(id, me);
        return ApiResponse.empty();
    }

    // ── 엑셀 일괄 업로드 ──

    /**
     * 미리보기 — <b>아무것도 저장하지 않는다.</b> "총 N행 중 M행 정상, K행 오류"를 먼저 보여준다.
     *
     * <p>결과를 서버에 들고 있지 않으므로 반영할 때 <b>같은 파일을 다시 올려야 한다.</b>
     * 세션에 보관하면 다중 인스턴스에서 어느 서버가 받을지 모른다.
     */
    @PostMapping("/import/preview")
    public ApiResponse<ImportResponse> previewImport(@CurrentAccount AuthPrincipal me,
                                                     @RequestParam Long academyId,
                                                     @RequestParam Integer year,
                                                     @RequestPart("file") MultipartFile file)
            throws IOException {
        return ApiResponse.success(ImportResponse.from(studentImportService.preview(
                file.getInputStream(), academyId, year.shortValue(), me)));
    }

    /** 반영 — <b>오류행이 있어도 정상행은 넣는다.</b> 100건 중 3건 틀렸다고 전부 되돌리면 실무가 안 돈다. */
    @PostMapping("/import")
    public ApiResponse<ImportResponse> importStudents(@CurrentAccount AuthPrincipal me,
                                                      @RequestParam Long academyId,
                                                      @RequestParam Integer year,
                                                      @RequestPart("file") MultipartFile file)
            throws IOException {
        return ApiResponse.success(ImportResponse.from(studentImportService.importStudents(
                file.getInputStream(), academyId, year.shortValue(), me)));
    }

    /** 재등록 — 같은 사람에 등록 건만 추가한다(상담 이력이 이어져야 하므로). */
    @PostMapping("/{studentId}/re-enroll")
    public ApiResponse<StudentResponse> reEnroll(@CurrentAccount AuthPrincipal me,
                                                 @PathVariable Long studentId,
                                                 @Valid @RequestBody StudentRequests.ReEnroll request) {
        return ApiResponse.success(StudentResponse.from(studentService.reEnroll(
                studentId, request.academyId(), request.year().shortValue(),
                request.grade(), request.track(), me)));
    }
}
