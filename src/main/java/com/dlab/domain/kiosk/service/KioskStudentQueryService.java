package com.dlab.domain.kiosk.service;

import com.dlab.api.kiosk.DsaApiException;
import com.dlab.api.kiosk.DsaCode;
import com.dlab.api.kiosk.dto.DsaRows;
import com.dlab.common.privacy.Masking;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.user.entity.ParentGuardian;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.AcademyRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import com.dlab.domain.user.repository.StudentGuardianLinkRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
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
 * <p>정본은 {@code DSA_Kiosk 연동규격서.doc}이고, 키오스크 백엔드 소스는
 * "실제로 어떻게 읽는지"의 보조 확인용이다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class KioskStudentQueryService {

    private static final DateTimeFormatter REG_DT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StudentEnrollmentRepository enrollmentRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final StudentGuardianLinkRepository guardianLinkRepository;
    private final AcademyRepository academyRepository;
    private final AbsenceReasonRepository absenceReasonRepository;
    private final Clock clock;

    /**
     * 3.20 {@code getStdInfoList} — 지점 학생 전체.
     *
     * <p><b>이름은 마스킹, {@code hp}는 뒷 4자리만</b> 내린다(규격서 샘플: {@code "홍*동"} /
     * {@code "1521"}). 키오스크는 여러 학생이 오가는 공용 화면이라 원본을 띄우면 안 된다.
     * 그래도 태깅 학생을 특정하는 데는 문제가 없다 — 카드가 본인을 지목한다.
     *
     * <p>⚠️ 규격서는 <b>"재원생과 대기생"</b>을 함께 내리라고 한다. 대기자 도메인이
     * 아직 없어(F-4.2-1, D-7·S-1 대기) 현재는 <b>재원생만</b> 나간다.
     * 대기생은 카드가 없어 태깅 대상이 아니므로 지금 누락돼도 동작에 영향은 없다.
     */
    public List<DsaRows.StudentRow> studentList(Long academyId) {
        Map<Long, String> seatByEnrollment = new HashMap<>();
        seatAssignmentRepository.findActiveByAcademyId(academyId)
                .forEach(sa -> seatByEnrollment.put(
                        sa.getEnrollment().getId(), sa.getSeat().getSeatCd()));

        // ★ 직원까지 내려보낸다 — 카드를 인식해야 키오스크에서 출퇴근을 찍는다.
        //   여기가 직원을 포함하는 유일한 경로다
        return enrollmentRepository.findCurrentIncludingStaff(academyId).stream()
                .map(e -> new DsaRows.StudentRow(
                        Masking.name(e.getStudent().getName()),
                        e.getStudentNo(),
                        e.getRfidNo(),
                        phoneTail(e.getStudent().getPhone()),
                        seatByEnrollment.get(e.getId())))
                .toList();
    }

    /** 규격서의 "휴대폰 번호 뒷자리" — 샘플이 4자리({@code "1521"})다. */
    private String phoneTail(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        return digits.length() <= 4 ? digits : digits.substring(digits.length() - 4);
    }

    /**
     * 3.24 {@code getStdInfo} — 학생 상세.
     *
     * <p>목록과 달리 <b>전체 번호</b>를 내린다 — 카드를 태깅한 본인 한 명만 나오는 화면이다.
     * 키오스크는 응답 목록을 <b>{@code std_no}로 한 번 더 필터</b>하므로 그 값을 반드시 싣는다.
     */
    public List<DsaRows.StudentDetailRow> studentDetail(Long academyId, String rfidNo) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);

        String guardianPhone = guardianLinkRepository
                .findByStudentId(enrollment.getStudent().getId()).stream()
                .map(link -> link.getGuardian().getPhone())
                .filter(phone -> phone != null && !phone.isBlank())
                .findFirst()
                .orElse(null);

        return List.of(new DsaRows.StudentDetailRow(
                enrollment.getStudent().getName(),
                enrollment.getStudentNo(),
                enrollment.getStudent().getPhone(),
                guardianPhone,
                // 주소는 우리 스키마에 없다. 키오스크도 읽지 않으므로 키만 유지한다
                null, null, null));
    }

    /**
     * 3.19 {@code getDlabList} — 지점 목록.
     *
     * <p><b>요청한 지점만이 아니라 전 지점을 내린다.</b> 키오스크가 지점 마스터를 동기화하는
     * 용도로 쓰고, 아무 지점 토큰으로나 호출한다.
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
     * <p>연락처는 <b>전체 번호</b>다(규격서 샘플 {@code "010-1111-2222"}) —
     * 학생이 부모에게 전화를 걸 수 있어야 하는 화면이다.
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

    /**
     * 성별 → DSA 관계 코드 (규격서: 부 {@code F} / 모 {@code M} / 기타 {@code E}).
     *
     * <p><b>글자가 뒤집히는 지점이라 별도 메서드로 뺐다.</b>
     * 우리 {@code gender}는 M=남/F=여인데 DSA {@code p_gb}는 F=Father/M=Mother다.
     */
    private String relationCode(String gender) {
        if (gender == null) {
            return "E";
        }
        return switch (gender.toUpperCase()) {
            case "M" -> "F";   // 남 → 부(Father)
            case "F" -> "M";   // 여 → 모(Mother)
            default -> "E";    // 기타
        };
    }

    /** 3.16 {@code getRequestListStd} — 당월 사유신청 목록. */
    public RequestList requestList(Long academyId, String rfidNo, String month) {
        StudentEnrollment enrollment = requireEnrollment(academyId, rfidNo);
        YearMonth target = parseMonth(month);
        String studentName = Masking.name(enrollment.getStudent().getName());

        List<DsaRows.RequestRow> rows = absenceReasonRepository.findByEnrollmentAndPeriod(
                        enrollment.getId(), target.atDay(1), target.atEndOfMonth()).stream()
                .map(r -> toRequestRow(r, enrollment.getStudentNo(), studentName))
                .toList();

        // 규격서는 hak_no·std_nm을 최상위에도 둔다 — 행마다 반복되지만 그대로 따른다
        return new RequestList(enrollment.getStudentNo(), studentName, rows);
    }

    /** {@code getRequestListStd}의 최상위 부가 필드 + 행. */
    public record RequestList(String hakNo, String stdNm, List<DsaRows.RequestRow> rows) {
    }

    private DsaRows.RequestRow toRequestRow(AbsenceReason r, String hakNo, String stdNm) {
        return new DsaRows.RequestRow(
                hakNo,
                stdNm,
                String.valueOf(r.getId()),
                r.getAttendanceDate().atStartOfDay().format(REG_DT),
                regGn(r.getReasonType()),
                state(r));
    }

    /**
     * 신청구분 (규격서 3.16: 휴가권 1 · 오전반휴권 2 · 오후반휴권 3 · 외출권 4 ·
     * 사유지각 5 · 사유조퇴 6 · 사유외출 7 …).
     *
     * <p><b>규격서 목록이 우리 {@link AbsenceReasonType} 4종보다 넓다</b> —
     * 반휴권·외출권(2·3·4)은 대응하는 개념이 우리 모델에 아직 없다. 사유 계열 3종(5·6·7)만
     * 정확히 대응하고 결석은 가장 가까운 휴가권(1)에 붙였다.
     * 사유 종류가 늘어나면 여기부터 확장한다.
     */
    private String regGn(AbsenceReasonType type) {
        if (type == null) {
            return null;
        }
        return switch (type) {
            case ABSENCE -> "1";        // 휴가권
            case LATE -> "5";           // 사유지각
            case EARLY_LEAVE -> "6";    // 사유조퇴
            case OUTING -> "7";         // 사유외출
        };
    }

    /**
     * 신청현황 (규격서: 대기 {@code D} · 승인 {@code S} · 미승인 {@code E}).
     *
     * <p>승인 요청이 아직 붙지 않은 건은 <b>대기</b>로 본다 — 제출은 됐고 라우팅 전이다.
     */
    private String state(AbsenceReason r) {
        if (r.getApprovalRequest() == null) {
            return "D";
        }
        ApprovalStatus status = r.getApprovalRequest().getStatus();
        if (status == null || status == ApprovalStatus.PENDING) {
            return "D";
        }
        return status == ApprovalStatus.APPROVED ? "S" : "E";
    }

    /** 형식이 깨진 {@code month}는 규격서 코드 102로 거절한다. */
    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.from(LocalDate.now(clock));
        }
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new DsaApiException(DsaCode.INVALID_MONTH);
        }
    }

    /**
     * 카드번호 → 현재 등록 건.
     *
     * <p><b>지점을 함께 확인한다.</b> 카드번호는 전역에서 유일하지 않아
     * (등록 건마다 이력이 쌓이고 다음 기수에 재사용된다) 확인하지 않으면
     * 다른 지점 학생이 조회될 수 있다.
     *
     * <p>실패 코드는 규격서의 {@code 101}(RFID NO 오류)이다.
     */
    private StudentEnrollment requireEnrollment(Long academyId, String rfidNo) {
        return enrollmentRepository.findCurrentByRfidNo(rfidNo)
                .filter(e -> e.getAcademy().getId().equals(academyId))
                .orElseThrow(() -> new DsaApiException(DsaCode.INVALID_KEY, "등록되지 않은 카드입니다."));
    }
}
