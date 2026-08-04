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

/**
 * 관리자 웹 — 학생 검색·신규 접수.
 *
 * <p>지점 스코프는 {@link SearchScope}가 인증 주체에서 뽑는다. 요청 파라미터로 받으면
 * 값을 바꿔 보내는 것만으로 다른 지점 학생이 조회된다(CLAUDE.md §7).
 */
@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','STAFF')")
public class AdminStudentController {

    private final StudentService studentService;
    private final StudentImportService studentImportService;
    private final StudentExportService studentExportService;
    private final SavedSearchService savedSearchService;

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
                request.gender(), request.schoolName(), request.grade(), request.track(),
                request.status(), me)));
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
