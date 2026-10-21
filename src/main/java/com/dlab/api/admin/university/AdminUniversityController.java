package com.dlab.api.admin.university;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.university.entity.University;
import com.dlab.domain.university.entity.UniversityDepartment;
import com.dlab.domain.university.repository.UniversityDepartmentRepository;
import com.dlab.domain.university.repository.UniversityRepository;
import com.dlab.domain.university.service.UniversityImportService;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 대학·학과 마스터 (디랩 1021 피드백 6-1).
 *
 * <p>실적 입력에서 대학·학과·계열을 <b>통일되게</b> 고르기 위한 마스터다. 지금까지는
 * 전부 자유 문자열이고 자동완성이 *"이미 입력된 값"* 에서 만들어져 <b>첫 오타가 그대로
 * 후보가 됐다.</b>
 *
 * <h2>화면 순서 — 대학 검색 → 학과 검색 → 계열 자동</h2>
 * ⚠️ <b>드롭다운으로 만들지 말 것.</b> 대학이 223개라 목록으로는 못 찾는다.
 * 계열은 학과를 고르면 따라오므로 <b>입력받지 않는다.</b>
 */
@Tag(name = "관리자 - 대학·학과 마스터")
@RestController
@RequestMapping("/api/v1/admin/universities")
@RequiredArgsConstructor
public class AdminUniversityController {

    private final UniversityImportService importService;
    private final UniversityRepository universityRepository;
    private final UniversityDepartmentRepository departmentRepository;

    /**
     * 대학 검색.
     *
     * <p>★ 캠퍼스가 이름에 들어 있어(「연세대(미래-원주)」) {@code 연세} 만 넣으면
     * 캠퍼스가 전부 나온다.
     *
     * @param keyword 비우면 전체. 223개라 화면에서는 보통 2글자 이상부터 호출한다
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    @Transactional(readOnly = true)
    public ApiResponse<List<UniversityView>> search(@RequestParam Integer year,
                                                    @RequestParam(required = false) String keyword) {
        return ApiResponse.success(
                universityRepository.search(year.shortValue(), blankToNull(keyword)).stream()
                        .map(UniversityView::from).toList());
    }

    /**
     * 그 대학의 학과 목록.
     *
     * <p>★ <b>대학코드로 받는다</b> — 학과코드만으로는 유일하지 않다(503개가 여러 대학
     * 공용). 응답에 계열이 함께 오므로 화면은 그 값을 그대로 채우면 된다.
     */
    @GetMapping("/{universityCode}/departments")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF','READONLY')")
    @Transactional(readOnly = true)
    public ApiResponse<List<DepartmentView>> departments(
            @PathVariable String universityCode,
            @RequestParam Integer year,
            @RequestParam(required = false) String keyword) {
        return ApiResponse.success(departmentRepository
                .search(year.shortValue(), universityCode, blankToNull(keyword)).stream()
                .map(DepartmentView::from).toList());
    }

    /**
     * 마스터 엑셀 적재.
     *
     * <p>★ <b>연도마다 새 파일이 온다</b>(원본 파일명이 {@code 2026더프모...20260223}).
     * 그래서 마이그레이션에 박지 않고 여기로 올린다 — 4,921행을 마이그레이션에 넣으면
     * 해마다 새 파일을 쓰게 되고, 이미 적용된 마이그레이션은 고칠 수 없다.
     *
     * <p>★ <b>재업로드가 행을 늘리지 않는다.</b> 같은 {@code (연도, 코드)} 는 갱신한다 —
     * 늘리면 검색에 같은 대학이 두 번 나오고 어느 쪽이 최신인지 알 수 없다.
     *
     * <p>⚠️ <b>본사만</b> 올린다. 전 지점 공통 마스터라 한 지점이 바꾸면 다른 지점 실적
     * 입력까지 흔들린다.
     */
    @PostMapping("/import")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ApiResponse<UniversityImportService.ImportResult> importMaster(
            @CurrentAccount AuthPrincipal me,
            @RequestParam Integer year,
            @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponse.success(
                importService.importMaster(year.shortValue(), file.getInputStream()));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record UniversityView(String code, String name) {

        static UniversityView from(University u) {
            return new UniversityView(u.getCode(), u.getName());
        }
    }

    /**
     * @param trackName 계열. ★ 화면이 이 값을 <b>자동으로 채운다</b> — 따로 입력받지 말 것
     */
    public record DepartmentView(String code, String name, String trackCode, String trackName) {

        static DepartmentView from(UniversityDepartment d) {
            return new DepartmentView(d.getCode(), d.getName(), d.getTrackCode(), d.getTrackName());
        }
    }
}
