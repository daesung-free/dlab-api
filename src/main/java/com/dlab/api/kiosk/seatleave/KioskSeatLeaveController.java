package com.dlab.api.kiosk.seatleave;

import com.dlab.common.response.ApiResponse;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.SeatLeaveIngestService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 좌석 이탈·복귀 수신 (F-4.3-2) — <b>DSA 호환이 아닌 신규 구획</b>.
 *
 * <h2>왜 {@code /kiosk/**}에 넣지 않았나</h2>
 * 기존 25개가 {@code code == 0} 판정에 {@code data} 이중 배열인 것은 <b>DSA가 그렇게
 * 생겼고 키오스크가 그 형식을 하드코딩하고 있어서</b>다. 이건 DSA에 없던 신규라
 * 흉내 낼 원본이 없다 — 거기에 옛 형식을 맞추면 이상한 형식만 하나 더 늘어난다.
 * 그래서 경로는 {@code /api/v1} prefix를 쓰고 응답은 {@link ApiResponse} 정상 형식이다.
 *
 * <p><b>인증만 기존 것을 그대로 쓴다.</b> 본문 {@code token}으로 지점을 해석한다 —
 * 키오스크가 이미 갖고 있어 새로 발급받을 것이 없고, 우리 JWT를 쓰게 하면
 * 키오스크에 로그인 흐름을 새로 만들어야 한다.
 *
 * <h2>키오스크가 지켜야 하는 것</h2>
 * <ul>
 *   <li><b>응답 200이 ack이다.</b> 200을 받은 행만 전송완료로 표시하고 나머지는 다시 보낸다
 *   <li>건별 결과 중 {@code DUPLICATE}·{@code ACCEPTED_UNRESOLVED}도 <b>전송완료</b>다 —
 *       다시 보내도 결과가 같다. {@code REJECTED}만 고쳐서 재전송한다
 *   <li>{@code sourceRowId}는 반드시 넣는다. 이게 없으면 재전송이 중복으로 쌓인다
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/kiosk")
@RequiredArgsConstructor
public class KioskSeatLeaveController {

    private final DsaTokenService tokenService;
    private final SeatLeaveIngestService ingestService;

    /**
     * 이탈·복귀 일괄 전송.
     *
     * <p><b>한 건이 틀려도 배치 전체를 거절하지 않는다</b> — 거절하면 키오스크가 그 배치를
     * 영원히 재전송하며 큐가 안 빠진다. 건별 결과를 돌려준다.
     */
    @PostMapping("/seat-leaves")
    public ApiResponse<List<SeatLeaveResponse>> ingest(
            @Valid @RequestBody SeatLeaveRequests.Ingest request) {

        return ApiResponse.success(
                ingestService.ingest(resolveAcademy(request.token()), request.toEvents()).stream()
                        .map(SeatLeaveResponse::from).toList());
    }

    /**
     * 토큰 → 지점.
     *
     * <p><b>DSA 예외를 우리 예외로 바꾼다.</b> {@code DsaApiException}을 그대로 두면
     * 이 구획에는 DSA 예외 핸들러가 걸려 있지 않아 500으로 나간다 — 신규 구획은
     * 우리 오류 계약({@code ApiResponse.error} + 정상 상태코드)을 따라야 한다.
     */
    private Long resolveAcademy(String token) {
        try {
            return tokenService.resolveAcademyId(token);
        } catch (com.dlab.api.kiosk.DsaApiException e) {
            throw new com.dlab.common.exception.BusinessException(
                    com.dlab.common.exception.ErrorCode.UNAUTHORIZED,
                    "키오스크 토큰이 유효하지 않습니다.");
        }
    }

    /** 건별 처리 결과. */
    public record SeatLeaveResponse(Long sourceRowId, String status, String message) {

        static SeatLeaveResponse from(SeatLeaveIngestService.Result result) {
            return new SeatLeaveResponse(result.sourceRowId(), result.status().name(),
                    result.message());
        }
    }
}
