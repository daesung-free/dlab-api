package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.api.kiosk.dto.DsaRows;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.seat.repository.SeatAssignmentRepository;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 키오스크 학생·지점·학부모 조회 (DSA 3.20 · 3.24 · 3.19 · 3.25 · 3.16).
 *
 * <p><b>검증 기준은 키오스크 백엔드의 파싱 코드다</b>({@code doc/kiosk/…/DsaStudentService} 등).
 * 필드명·폴백 순서를 거기 맞춘다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskStudentQueryService {

    private final StudentEnrollmentRepository enrollmentRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final StudentGuardianLinkRepository guardianLinkRepository;
    private final AcademyRepository academyRepository;
    private final AbsenceReasonRepository absenceReasonRepository;
    private final Clock clock;

    /**
     * 3.20 {@code getStdInfoList} — 지점 학생 전체.
     *
     * <p>키오스크는 {@code rfid_no}나 {@code std_nm}이 없는 행을 <b>조용히 버린다</b>.
     * 카드 미발급자는 애초에 태깅할 수 없으니 버려져도 맞지만, 그래서 누락이 로그에만 남고
     * 화면에는 안 보인다는 걸 알고 있어야 한다.
     */
    public List<DsaRows.StudentRow> studentList(Long academyId) {
        Map<Long, String> seatByEnrollment = new HashMap<>();
        seatAssignmentRepository.findActiveByAcademyId(academyId)
                .forEach(sa -> seatByEnrollment.put(
                        sa.getEnrollment().getId(), sa.getSeat().getSeatCd()));

        return enrollmentRepository.findCurrentByAcademyId(academyId).stream()
                .map(e -> new DsaRows.StudentRow(
                        e.getStudent().getName(),
                        e.getStudentNo(),
                        e.getRfidNo(),
                        e.getStudent().getPhone(),
                        seatByEnrollment.get(e.getId())))
                .toList();
    }

    /**
     * 3.24 {@code getStdInfo} — 카드번호로 학생 연락처.
     *
     * <p>키오스크가 응답 목록에서 <b>{@code std_no}로 한 번 더 필터</b>하므로 그 값을 반드시 싣는다.
     * 그리고 {@code hp}가 없으면 {@code p_hp}로 폴백하므로 보호자 번호도 함께 내린다 —
     * 학생 본인 번호가 없는 재원생이 실제로 있다.
     */
    public List<DsaRows.StudentPhoneRow> studentPhone(Long academyId, String rfidNo) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);

        String guardianPhone = guardianLinkRepository
                .findByStudentId(enrollment.getStudent().getId()).stream()
                .map(link -> link.getGuardian().getPhone())
                .filter(phone -> phone != null && !phone.isBlank())
                .findFirst()
                .orElse(null);

        return List.of(new DsaRows.StudentPhoneRow(
                enrollment.getStudentNo(),
                enrollment.getStudent().getPhone(),
                guardianPhone));
    }

    /**
     * 3.19 {@code getDlabList} — 지점 목록.
     *
     * <p><b>요청한 지점만이 아니라 전 지점을 내린다.</b> 키오스크가 지점 마스터를 동기화하는
     * 용도로 쓰고, 아무 지점 토큰으로나 호출한다(그쪽 {@code DsaStoreService} 주석 명시).
     */
    public List<DsaRows.AcademyRow> academyList() {
        return academyRepository.findAll().stream()
                .filter(a -> !a.isDeleted() && a.isActive())
                .map(a -> new DsaRows.AcademyRow(
                        a.getAcadCd(), a.getAcadNm(), a.getFullNmOrFallback()))
                .toList();
    }

    /**
     * 3.25 {@code getParentHpList} — 보호자 연락처.
     *
     * <p>⚠️ <b>{@code p_gb}는 성별이 아니라 관계다.</b> 키오스크가 {@code F → "부"},
     * {@code M → "모"}로 표시한다. 우리 {@code gender}는 {@code M}=남/{@code F}=여라
     * <b>글자가 정반대로 겹친다</b> — 그대로 넘기면 아버지가 "모"로 뜬다.
     */
    public List<DsaRows.ParentPhoneRow> parentPhones(Long academyId, String rfidNo) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);

        return guardianLinkRepository.findByStudentId(enrollment.getStudent().getId()).stream()
                .map(link -> {
                    ParentGuardian g = link.getGuardian();
                    return new DsaRows.ParentPhoneRow(relationCode(g.getGender()), g.getPhone());
                })
                .toList();
    }

    /** 성별 → DSA 관계 코드. 겹치는 글자를 뒤집는 지점이라 별도 메서드로 뺐다. */
    private String relationCode(String gender) {
        if (gender == null) {
            return null;   // 키오스크가 "기타"로 표시한다
        }
        return switch (gender.toUpperCase()) {
            case "M" -> "F";   // 남 → 부(Father)
            case "F" -> "M";   // 여 → 모(Mother)
            default -> null;
        };
    }

    /**
     * 3.16 {@code getRequestListStd} — 당월 사유신청 목록.
     *
     * <p>⚠️ <b>{@code reg_gn}의 실제 값 목록이 확인되지 않았다</b>(§4 블로커, D-2).
     * 키오스크는 이 값을 받아 담아두기만 하고 <b>소비하는 코드가 없어</b>
     * (그쪽 {@code DsaRequestService.ApprovedRequest}가 미사용) 지금은 우리 사유 구분을
     * 그대로 실어도 깨지지 않는다. 실제 값 목록을 확보하면 여기만 바꾸면 된다.
     */
    public List<DsaRows.RequestRow> requestList(Long academyId, String rfidNo, String month) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        YearMonth target = parseMonth(month);

        return absenceReasonRepository.findByEnrollmentAndPeriod(
                        enrollment.getId(), target.atDay(1), target.atEndOfMonth()).stream()
                .map(this::toRequestRow)
                .toList();
    }

    private DsaRows.RequestRow toRequestRow(AbsenceReason r) {
        return new DsaRows.RequestRow(
                String.valueOf(r.getId()),
                r.getAttendanceDate().toString(),
                r.getReasonType() == null ? null : r.getReasonType().name());
    }

    /** 형식이 깨진 {@code month}는 당월로 본다 — 빈 목록보다 낫고, 키오스크는 재시도하지 않는다. */
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(clock));
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            return YearMonth.from(LocalDate.now(clock));
        }
    }

    /**
     * 카드번호 → 현재 등록 건.
     *
     * <p><b>지점을 함께 확인한다.</b> 카드번호는 전역에서 유일하지 않아
     * (등록 건마다 이력이 쌓이고 다음 기수에 재사용된다) 확인하지 않으면
     * 다른 지점 학생이 조회될 수 있다.
     */
    private StudentEnrollment requireEnrollment(Long academyId, String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.NO_APPROVAL, "등록되지 않은 카드입니다."));
    }
}
