package com.dlab.api.kiosk.penalty;

import com.dlab.api.kiosk.dto.DateRangeRequest;
import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskPenaltyQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 키오스크 상벌점 조회 (3.28). <b>지점 전체</b>를 내린다 — 학생 단건이 아니다. */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskPenaltyController {

    private final KioskPenaltyQueryService penaltyQueryService;
    private final DsaTokenService tokenService;

    @PostMapping("/getPointStdList")
    public DsaResponse points(@RequestBody DateRangeRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(penaltyQueryService.points(
                academyId, request.startDate(), request.endDate()));
    }
}
