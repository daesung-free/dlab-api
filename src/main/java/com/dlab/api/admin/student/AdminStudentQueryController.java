package com.dlab.api.admin.student;

import com.dlab.api.admin.student.dto.StudentSummaryResponse;
import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.user.entity.EnrollmentStatus;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.entity.TrackType;
import com.dlab.domain.user.repository.StudentSearchCondition;
import com.dlab.domain.user.service.StudentQueryService;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 학생 조회 (F-4.1-1).
 *
 * <p>등록·수정은 별도 컨트롤러다 — 담당이 갈려 있다.
 *
 * <p><b>{@code academyId}를 파라미터로 받지 않는다.</b> 지점 스코프는 인증 주체에서만 나온다 —
 * 받으면 값을 바꿔 보내는 것만으로 다른 지점 학생이 조회된다(CLAUDE.md §7).
 */
@RestController
@RequestMapping("/api/v1/admin/students")
@RequiredArgsConstructor
public class AdminStudentQueryController {

    private final StudentQueryService studentQueryService;

    /**
     * 통합 검색. {@code keyword} 하나로 이름·학번·전화번호를 함께 본다.
     *
     * <p>정렬은 {@code sort=studentNo,asc} 형태. 허용되지 않은 필드는 무시된다.
     */
    @GetMapping
    public ApiResponse<List<StudentSummaryResponse>> search(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String studentNo,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) GradeType grade,
            @RequestParam(required = false) TrackType track,
            @RequestParam(required = false) List<EnrollmentStatus> statuses,
            @RequestParam(required = false) Long classId,
            @RequestParam(required = false) String schoolName,
            @RequestParam(required = false) String gender,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate admittedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate admittedTo,
            @RequestParam(required = false) Boolean hasRfid,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<StudentEnrollment> page = studentQueryService.search(me,
                new StudentSearchCondition(keyword, year, name, studentNo, phone, grade, track,
                        statuses, classId, schoolName, gender, admittedFrom, admittedTo, hasRfid),
                pageable);

        return ApiResponse.success(
                page.getContent().stream().map(StudentSummaryResponse::from).toList(),
                ApiResponse.PageMeta.of(page));
    }

    @GetMapping("/{enrollmentId}")
    public ApiResponse<StudentSummaryResponse> get(
            @CurrentAccount AuthPrincipal me,
            @PathVariable Long enrollmentId) {
        return ApiResponse.success(
                StudentSummaryResponse.from(studentQueryService.getEnrollment(me, enrollmentId)));
    }

    /**
     * 목록 엑셀 다운로드. <b>검색 조건에 맞는 전건</b>이 나간다 — 화면 페이지가 아니다.
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @CurrentAccount AuthPrincipal me,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) GradeType grade,
            @RequestParam(required = false) TrackType track,
            @RequestParam(required = false) List<EnrollmentStatus> statuses,
            @RequestParam(required = false) Long classId) {

        byte[] body = studentQueryService.export(me,
                new StudentSearchCondition(keyword, year, null, null, null, grade, track,
                        statuses, classId, null, null, null, null, null));

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                // 한글 파일명이 깨지지 않게 RFC 5987 인코딩을 쓴다
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''%EC%9E%AC%EC%9B%90%EC%83%9D.xlsx")
                .body(body);
    }
}
