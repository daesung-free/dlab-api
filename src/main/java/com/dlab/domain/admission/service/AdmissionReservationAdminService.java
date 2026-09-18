package com.dlab.domain.admission.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.admission.entity.*;
import com.dlab.domain.admission.repository.*;
import com.dlab.domain.user.entity.GradeType;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.service.StudentService;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 입학예약 관리 (F-4.2-1 대기자 관리).
 *
 * <h2>신청은 이미 들어온다</h2>
 * 홈페이지 수신({@code POST /dlab/setStdInfo})은 규격서대로 동작한다. <b>없던 것은
 * 관리자가 보는 쪽</b>이다 — 목록·상태·메모·정식 접수 전환.
 *
 * <h2>★ 전환은 「입학확정」에서만 한다</h2>
 * 상담 상태 6종과 재적 상태는 다른 축이고, <b>둘이 만나는 지점이 입학확정 하나</b>다.
 * 아무 상태에서나 전환되게 두면 상담취소된 신청자에게 학번이 나간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionReservationAdminService {

    private static final DateTimeFormatter BIRTH = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AdmissionReservationRepository reservationRepository;
    private final AdmissionReservationMemoRepository memoRepository;
    private final AdmissionReservationStatusLogRepository statusLogRepository;
    private final StudentService studentService;

    /**
     * 목록.
     *
     * <p>★ <b>지점은 권한에서 온다.</b> 전 지점 권한자면 조건이 빠지고, 아니면 자기 지점만
     * 본다 — 요청 파라미터로 받으면 바꿔 보내는 것만으로 남의 지점이 열린다.
     */
    @Transactional(readOnly = true)
    public List<AdmissionReservation> search(AuthPrincipal me, short year,
                                             ConsultStatus status, String keyword) {
        Long scope = me.allAcademy() ? null : me.academyId();
        // ★ null 을 넘기면 PostgreSQL 이 bytea 로 추론해 LIKE 가 깨진다. 패턴을 만들어 넘긴다
        String pattern = keyword == null || keyword.isBlank() ? "%" : "%" + keyword.trim() + "%";
        return reservationRepository.search(scope, year, status, pattern);
    }

    @Transactional(readOnly = true)
    public AdmissionReservation get(AuthPrincipal me, Long reservationId) {
        return require(me, reservationId);
    }

    @Transactional(readOnly = true)
    public List<AdmissionReservationMemo> memos(AuthPrincipal me, Long reservationId) {
        require(me, reservationId);
        return memoRepository.findByReservationId(reservationId);
    }

    @Transactional(readOnly = true)
    public List<AdmissionReservationStatusLog> statusLogs(AuthPrincipal me, Long reservationId) {
        require(me, reservationId);
        return statusLogRepository.findByReservationId(reservationId);
    }

    /**
     * 상태 변경.
     *
     * <p><b>이력을 남긴다</b> — 없으면 "왜 미등록으로 바뀌었나" 에 답할 수 없다.
     */
    @Transactional
    public AdmissionReservation changeStatus(AuthPrincipal me, Long reservationId,
                                             ConsultStatus status, String reason) {
        AdmissionReservation reservation = require(me, reservationId);
        ConsultStatus from = reservation.getConsultStatus();
        if (from == status) {
            return reservation;
        }
        reservation.changeStatus(status);
        statusLogRepository.save(
                new AdmissionReservationStatusLog(reservation, from, status, reason));
        return reservation;
    }

    @Transactional
    public AdmissionReservationMemo addMemo(AuthPrincipal me, Long reservationId, String content) {
        AdmissionReservation reservation = require(me, reservationId);
        return memoRepository.save(new AdmissionReservationMemo(reservation, content));
    }

    @Transactional
    public void deleteMemo(AuthPrincipal me, Long memoId) {
        AdmissionReservationMemo memo = memoRepository.findById(memoId)
                .filter(m -> !m.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "메모를 찾을 수 없습니다."));
        me.requireAcademyScope(memo.getAcademy().getId());
        memo.markDeleted();
    }

    /**
     * 정식 접수 전환 — 신청자를 학생으로 만든다.
     *
     * <p>★ <b>입학확정 상태에서만 된다.</b> 상담취소·미등록 건에 학번이 나가면 그 학번은
     * 회수되지 않는다.
     *
     * <p>★ <b>두 번 전환하지 않는다.</b> 같은 신청으로 학번이 두 개 생기면 출결·수납이
     * 갈라진다. 학번 채번은 기존 신규 접수 경로를 그대로 쓴다 — 동시 가입 시 유니크 제약
     * 재시도가 이미 거기 있다.
     */
    @Transactional
    public StudentEnrollment convert(AuthPrincipal me, Long reservationId,
                                     GradeType grade, LocalDate admissionDate) {
        AdmissionReservation reservation = require(me, reservationId);

        if (reservation.getConsultStatus() != ConsultStatus.CONFIRMED) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "「입학확정」 상태에서만 정식 접수로 전환합니다. 현재 %s 입니다."
                            .formatted(reservation.getConsultStatus().displayName()));
        }
        if (reservation.converted()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "이미 학생으로 전환된 신청입니다. 학번 %s"
                            .formatted(reservation.getEnrollment().getStudentNo()));
        }

        StudentEnrollment enrollment = studentService.admit(
                reservation.getAcademy().getId(), reservation.getYear(),
                reservation.getStudentName(), reservation.getStudentTel(),
                grade == null ? gradeOf(reservation) : grade,
                null, null, birthOf(reservation), reservation.getGender(),
                reservation.getSchNmHigh(), addressOf(reservation),
                admissionDate, me);

        reservation.linkEnrollment(enrollment);
        log.info("입학예약 정식 접수 전환: reservationId={}, studentNo={}",
                reservationId, enrollment.getStudentNo());
        return enrollment;
    }

    /**
     * 신청서의 학년 표기를 우리 값으로 옮긴다.
     *
     * <p>규격서가 {@code 1·2·3·N} 으로 주는데 우리는 {@code HIGH2/HIGH3/N_SU} 3종이다.
     * <b>고1 은 대응하는 값이 없다</b> — 재수 학원이라 실제로 오지 않지만, 오면 사람이
     * 골라야 하므로 그때는 요청에 담아 보내게 한다.
     */
    private GradeType gradeOf(AdmissionReservation reservation) {
        return switch (reservation.getStdGrade()) {
            case "2" -> GradeType.HIGH2;
            case "3" -> GradeType.HIGH3;
            case "N" -> GradeType.N_SU;
            default -> throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "학년(%s)을 신청서에서 판단할 수 없습니다. 학년을 지정해 주세요."
                            .formatted(reservation.getStdGrade()));
        };
    }

    /** 규격서가 {@code yyyyMMdd} 문자열로 준다. 값이 이상하면 비운다 — 전환을 막을 일은 아니다 */
    private LocalDate birthOf(AdmissionReservation reservation) {
        String birth = reservation.getBirth();
        if (birth == null || birth.length() != 8) {
            return null;
        }
        try {
            return LocalDate.parse(birth, BIRTH);
        } catch (Exception e) {
            return null;
        }
    }

    private String addressOf(AdmissionReservation reservation) {
        if (reservation.getAddr1() == null) {
            return null;
        }
        return reservation.getAddr2() == null
                ? reservation.getAddr1()
                : reservation.getAddr1() + " " + reservation.getAddr2();
    }

    private AdmissionReservation require(AuthPrincipal me, Long reservationId) {
        AdmissionReservation reservation = reservationRepository.findById(reservationId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "입학예약 신청을 찾을 수 없습니다."));
        me.requireAcademyScope(reservation.getAcademy().getId());
        return reservation;
    }
}
