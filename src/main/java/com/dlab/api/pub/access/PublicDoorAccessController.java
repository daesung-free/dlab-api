package com.dlab.api.pub.access;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.access.service.DoorCodeRequestService;
import com.dlab.domain.audit.AuditContext;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 긴급 출입 번호 제공 — <b>공개 경로</b> (긴급출입 1-6).
 *
 * <p>출입문에 붙은 QR 을 찍으면 열리는 페이지다. <b>로그인이 없다</b> — 문 앞에 서 있는
 * 학생이 앱을 깔고 로그인할 상황이 아니라서다.
 *
 * <h2>★★ 거절 이유를 학생에게 알려주지 않는다</h2>
 * 어느 조건에 걸렸는지 알려주면 <b>번호를 두드려 보는 사람에게 힌트</b>가 된다 —
 * 「그 번호는 재원생이 아닙니다」는 곧 「그 번호는 우리 학생입니다」의 반대 정보다.
 * 응답은 전부 같은 문구이고, <b>갈라서 보는 것은 관리자 내역</b>이다.
 *
 * <h2>★ 거절도 HTTP 200 이다</h2>
 * 「확인되지 않았다」는 <b>정상 처리 결과</b>라 {@code provided: false} 로 내린다.
 * 4xx 로 내리면 화면이 오류 처리로 빠져 같은 문구를 띄우기 어렵고, 응답 코드만 보고도
 * 조건별 차이가 드러난다.
 */
@Tag(name = "공개 · 긴급 출입")
@RestController
@RequestMapping("/api/v1/public/door")
@RequiredArgsConstructor
public class PublicDoorAccessController {

    private final DoorCodeRequestService doorCodeRequestService;

    /**
     * 번호 요청.
     *
     * <p>⚠️ <b>시도 횟수에 제한이 있다.</b> 무인증 경로라 제한이 없으면 번호를 바꿔 가며
     * 두드려 도어락 번호를 뽑아낼 수 있다. 막힌 요청도 거절과 같은 응답으로 내린다 —
     * <b>제한에 걸린 사실을 알려주면 우회 간격을 역산할 수 있다.</b>
     *
     * @param acadCd 출입문 QR 이 담은 지점코드
     */
    @PostMapping("/{acadCd}/code")
    public ApiResponse<CodeResponse> request(@PathVariable String acadCd,
                                             @Valid @RequestBody CodeRequest body) {

        var outcome = doorCodeRequestService.request(
                acadCd, body.phone(), AuditContext.actorIp());

        return ApiResponse.success(
                new CodeResponse(outcome.provided(), outcome.code(), outcome.message()));
    }

    /** @param phone 학생 본인 휴대폰 번호. 하이픈이 섞여 와도 된다 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PublicDoorCodeRequest")
    public record CodeRequest(@NotBlank(message = "휴대폰 번호를 입력해 주세요.")
                              @Size(max = 20) String phone) {
    }

    /**
     * @param code    제공한 번호. <b>거절이면 {@code null}</b>
     * @param message 거절 문구. 제공되면 {@code null} — <b>이유는 담지 않는다</b>
     */
    @io.swagger.v3.oas.annotations.media.Schema(name = "PublicDoorCodeResponse")
    public record CodeResponse(boolean provided, String code, String message) {
    }
}
