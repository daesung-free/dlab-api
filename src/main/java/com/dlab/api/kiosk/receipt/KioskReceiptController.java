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

    @PostMapping("/getReceiptInfo")
    public DsaResponse receipts(@RequestBody RfidRequest request) {
        Long academyId = tokenService.resolveAcademyId(request.token());
        return DsaResponse.ok(receiptQueryService.receipts(academyId, request.rfidNo()));
    }
}
