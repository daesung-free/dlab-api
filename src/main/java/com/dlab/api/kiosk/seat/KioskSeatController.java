package com.dlab.api.kiosk.seat;

import com.dlab.api.kiosk.dto.AreaRequest;
import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.api.kiosk.dto.TokenOnlyRequest;
import com.dlab.api.kiosk.dto.SeatChangeRequest;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskSeatChangeService;
import com.dlab.domain.kiosk.service.KioskSeatQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 좌석 조회.
 *
 * <p>3.8(레이아웃)과 3.10(상태)은 <b>키오스크가 따로 호출해 {@code seat_cd}로 머지</b>한다.
 * 한쪽에만 있는 좌석은 상태가 기본값 {@code B}로 잡히므로 두 응답의 코드가 어긋나면 안 된다.
 */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskSeatController {

    private final KioskSeatQueryService seatQueryService;
    private final KioskSeatChangeService seatChangeService;
    private final DsaTokenService tokenService;

    /** 3.7 — 구역 목록. */
    @PostMapping("/getStudyAreaInfo")
    public DsaResponse areas(@RequestBody TokenOnlyRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(seatQueryService.areas(academyId));
    }

    /** 3.8 — 구역별 좌석 레이아웃(좌표 포함). */
    @PostMapping("/getStudyAreaSeatInfo")
    public DsaResponse seats(@RequestBody AreaRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(seatQueryService.seats(academyId, request.areaCd()));
    }

    /** 3.10 — 좌석 실시간 상태. */
    @PostMapping("/getStudyAreaSeatState")
    public DsaResponse seatStates(@RequestBody AreaRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(seatQueryService.seatStates(academyId, request.areaCd()));
    }

    /**
     * 3.23 — 좌석 변경. 응답은 {@code code}·{@code message}만이다(규격서).
     *
     * <p>키오스크는 자기 쪽 변경을 마친 뒤 동기화로 부르고 실패 시 재시도할 수 있으므로,
     * <b>이미 그 자리면 성공으로 응답한다</b>(멱등).
     */
    @PostMapping("/setSeatChgProc")
    public DsaResponse changeSeat(@RequestBody SeatChangeRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        seatChangeService.changeSeat(academyId, request.rfidNo(), request.seatCd());
        return DsaResponse.ok();
    }
}
