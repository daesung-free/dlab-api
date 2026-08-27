package com.dlab.api.kiosk.receipt;

import com.dlab.api.kiosk.dto.DsaResponse;
import com.dlab.api.kiosk.dto.RfidRequest;
import com.dlab.domain.kiosk.service.DsaTokenService;
import com.dlab.domain.kiosk.service.KioskReceiptQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 키오스크 수납 조회 (3.29).
 *
 * <p>학생 단건이다 — 카드로 조회한다.
 */
@RestController
@RequestMapping("/kiosk")
@RequiredArgsConstructor
public class KioskReceiptController {

    private final KioskReceiptQueryService receiptQueryService;
    private final DsaTokenService tokenService;

    /**
     * 수납 내역 (규격서 3.29).
     *
     * <p><b>완납 건도 내린다</b> — 미납만 주면 "낸 것"이 화면에서 사라진다.
     *
     * <p>⚠️ 청구 한 줄이 곧 한 항목이다. 교습비·독서실비는 그 아래 항목으로 나뉘어 있지만
     * <b>여기서는 합계 한 줄로 나간다</b> — 쪼개서 내리면 키오스크 영수증이 바뀐다.
     */
    @PostMapping("/getReceiptInfo")
    public DsaResponse receipts(@RequestBody RfidRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(receiptQueryService.receipts(academyId, request.rfidNo()));
    }
}
