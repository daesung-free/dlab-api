package com.dlab.api.admin.admission;

import com.dlab.common.response.ApiResponse;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.CurrentAccount;
import com.dlab.domain.admission.entity.CommonCode;
import com.dlab.domain.admission.repository.CommonCodeRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 입학예약 선택 항목 값 목록 — <b>관리자 화면용</b>.
 *
 * <h2>★ 홈페이지 쪽({@code /api/v1/homepage/codes})과 왜 따로 두는가</h2>
 * 그쪽은 <b>고정 키</b>로 들어오는 입구다. 관리자 화면은 직원 JWT 로만 호출하고, 그 키를
 * 프론트 번들에 넣으면 <b>브라우저에서 읽힌다</b> — 넣을 수 없다. 같은 값을 보지만 인증이
 * 다르므로 입구를 가른다.
 *
 * <h2>지점은 토큰에서 온다</h2>
 * 홈페이지 쪽은 요청의 학원코드({@code F}·{@code I}·{@code T}…)가 지점을 정하지만 여기는
 * 계정이 정한다. <b>파라미터로 받지 않는다</b> — 바꿔 보내는 것만으로 다른 지점 항목이 열린다.
 *
 * <h2>⚠️ 지금은 비어 있다</h2>
 * 값 목록(출신학원 {@code ACAD} · 지원기준 {@code ADMI} · 전형 {@code EXAM} ·
 * 알게된 경로 {@code FIND})을 아직 받지 못했다. 빈 배열이 정상이고, 받으면 행만 넣으면 된다.
 * <b>그때까지 화면은 코드 숫자를 그대로 보여주지 않는다</b> — 「유입경로 3」은 담당자가
 * 읽어도 할 일이 달라지지 않는다.
 */
@Tag(name = "관리자 · 입학예약 코드")
@RestController
@RequestMapping("/api/v1/admin/admission-codes")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN','BRANCH_ADMIN','TEACHER','STAFF')")
public class AdminAdmissionCodeController {

    private final CommonCodeRepository commonCodeRepository;

    /**
     * 그룹별 값 목록. 전 지점 공통과 내 지점 것을 함께 준다.
     *
     * @param group {@code ACAD}(출신학원) · {@code ADMI}(지원기준) · {@code EXAM}(전형) ·
     *              {@code FIND}(알게된 경로) · {@code SUBJECT}(과목)
     */
    @GetMapping
    public ApiResponse<List<CodeView>> codes(@CurrentAccount AuthPrincipal me,
                                             @RequestParam String group) {
        return ApiResponse.success(
                commonCodeRepository.findByGroup(group.toUpperCase(), me.academyId()).stream()
                        .map(CodeView::from)
                        .toList());
    }

    /**
     * @param idx 규격서의 순번. 과목처럼 순번으로 넘기는 항목이 있어 함께 내린다
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AdminAdmissionCodeView")
    public record CodeView(String group, String code, String name, Short idx) {

        static CodeView from(CommonCode c) {
            return new CodeView(c.getGrp(), c.getCode(), c.getName(), c.getIdx());
        }
    }
}
