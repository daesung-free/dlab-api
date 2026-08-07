package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.dto.DsaRows;
import com.dlab.domain.payment.entity.Billing;
import com.dlab.domain.payment.repository.BillingRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 수납 조회 (DSA 3.29 {@code getReceiptInfo}).
 *
 * <p><b>실패해도 키오스크는 조용하다.</b> 그쪽은 예외를 삼키고 빈 배열로 폴백해
 * 화면만 비운다({@code StudentDetailDsaService.findReceipts}) — 우리가 안 만들면
 * 학생은 "수납 내역이 없다"고 읽는다.
 *
 * <p>모르는 카드는 <b>빈 목록</b>이다. 여기서 예외를 던지면 학생 상세 화면 전체가
 * 실패로 떨어진다 — 수납은 그 화면의 한 조각일 뿐이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskReceiptQueryService {

    private final BillingRepository billingRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    public List<DsaRows.ReceiptRow> receipts(Long academyId, String rfidNo) {
        if (rfidNo == null || rfidNo.isBlank()) {
            return List.of();
        }
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .map(StudentEnrollment::getId)
                .map(billingRepository::findByEnrollment)
                .orElse(List.of())
                .stream()
                .map(this::toRow)
                .toList();
    }

    /** 금액은 문자열로 내린다 — 상벌점 {@code point}와 같은 규약이다. */
    private DsaRows.ReceiptRow toRow(Billing b) {
        return new DsaRows.ReceiptRow(
                b.getName(),
                String.valueOf(b.getSuppliedAmount()),
                String.valueOf(b.receivedAmount()),
                String.valueOf(b.unpaidAmount()));
    }
}
