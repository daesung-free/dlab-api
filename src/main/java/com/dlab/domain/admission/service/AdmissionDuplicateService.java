package com.dlab.domain.admission.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.domain.admission.entity.AdmissionDuplicateDecision;
import com.dlab.domain.admission.entity.AdmissionReservation;
import com.dlab.domain.admission.repository.AdmissionReservationRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 홈페이지 입학예약 ↔ 데스크 신규접수 <b>중복 탐지</b> (피드백 6-3①).
 *
 * <h2>무엇이 비어 있었나</h2>
 * 같은 입학예약을 <b>두 번 전환</b>하는 것은 이미 막혀 있다({@code reservation.converted()}).
 * 그러나 「홈페이지 예약」과 「데스크 신규접수」는 <b>서로 다른 경로</b>라 겹치는 것을
 * 막는 처리가 없었다 — 홈페이지로 상담 예약을 넣은 학생을 데스크에서 그냥 등록하면
 * <b>같은 사람이 두 건으로 쌓인다.</b> 한쪽은 대기자 목록에 남고 한쪽은 재원생이 되어,
 * 나중에 둘을 잇는 단서가 이름뿐이다.
 *
 * <h2>★★ 자동 병합은 하지 않는다</h2>
 * 동명이인이 실제로 있고, 잘못 합치면 <b>되돌릴 수 없다</b> — 전환은 학번을 채번하고
 * 학번은 회수되지 않는다. 그래서 <b>찾아서 알려주고 사람이 고르게</b> 한다. 저장을 막고
 * 후보를 띄우는 것까지가 서버의 일이다.
 *
 * <h2>비교는 숫자만 뽑아서 한다</h2>
 * 생년은 규격서 필드라 {@code yyyyMMdd} <b>문자열</b>이고({@link AdmissionReservation#getBirth()}),
 * 연락처는 하이픈이 섞여 들어온다. 날짜로 변환해 비교하면 형식이 다른 값에서 터지고,
 * 문자열을 그대로 비교하면 {@code 010-1234-5678}과 {@code 01012345678}이 다른 번호가 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdmissionDuplicateService {

    private static final DateTimeFormatter BIRTH = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final AdmissionReservationRepository reservationRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final com.dlab.domain.admission.repository.AdmissionDuplicateDecisionRepository decisionRepository;

    /**
     * 데스크 신규접수 → <b>같은 사람의 입학예약</b>을 찾는다.
     *
     * <p>판정은 <b>[이름 + 생년월일] 또는 [이름 + 연락처]</b>다. 이름만으로는 동명이인이
     * 걸리고, 셋을 모두 요구하면 데스크가 생년월일을 안 받은 건에서 아무것도 안 걸린다.
     *
     * <p>⚠️ 데스크 접수 폼에는 <b>학부모 연락처 칸이 없다.</b> 그래서 들어온 연락처 하나를
     * 예약의 학생·학부모 번호 <b>양쪽과</b> 비교한다 — 실제로 학부모 번호를 학생 연락처
     * 칸에 적는 경우가 많고, 한쪽만 보면 그 건이 전부 빠져나간다.
     *
     * @param academyId <b>이미 검증된</b> 지점. 요청 값을 그대로 넘기지 말 것
     * @param tel       학생 또는 학부모 연락처. 비어도 된다
     */
    public List<Match> findReservations(Long academyId, short year, String name,
                                        String birth, String tel) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String birthDigits = digits(birth);
        String telDigits = digits(tel);
        if (birthDigits == null && telDigits == null) {
            // 이름만으로는 판정하지 않는다 — 동명이인이 전부 「같은 분」으로 뜬다
            return List.of();
        }

        List<Match> matches = new ArrayList<>();
        for (AdmissionReservation r : reservationRepository
                .findUnconvertedByName(academyId, year, name.trim())) {
            MatchedBy by = matchedBy(r, birthDigits, telDigits);
            if (by != null) {
                matches.add(new Match(r, by));
            }
        }
        return matches;
    }

    /**
     * 입학예약 전환 → <b>이미 등록된 같은 학생</b>을 찾는다. 반대 방향이다.
     *
     * <p>데스크가 먼저 등록하고 나중에 예약을 전환하는 순서도 실제로 일어난다. 한쪽만
     * 막으면 순서를 바꾸는 것만으로 중복이 통과한다.
     *
     * <p>⚠️ 학생 쪽에는 <b>학부모 연락처가 따로 없다</b>(보호자 연결은 앱 가입 이후에
     * 생긴다). 그래서 예약의 학생·학부모 번호를 학생 연락처 한 칸과 비교한다 — 전환
     * 시점에는 보호자 연결이 아직 없는 것이 보통이라 이게 쓸 수 있는 전부다.
     */
    public List<EnrollmentMatch> findEnrollments(AdmissionReservation reservation) {
        String birthDigits = digits(reservation.getBirth());
        String studentTel = digits(reservation.getStudentTel());
        String parentTel = digits(reservation.getParentTel());
        if (birthDigits == null && studentTel == null && parentTel == null) {
            return List.of();
        }

        // ★ 이미 「다른 사람」으로 정리한 등록 건은 다시 묻지 않는다.
        //   이게 없으면 담당자가 **같은 판단을 볼 때마다 다시** 해야 한다
        List<Long> decided = decisionRepository.decidedEnrollmentIds(reservation.getId());

        List<EnrollmentMatch> matches = new ArrayList<>();
        for (StudentEnrollment e : enrollmentRepository.findByName(
                reservation.getAcademy().getId(), reservation.getYear(),
                reservation.getStudentName())) {
            String enrolledBirth = digits(e.getStudent().getBirthDate() == null
                    ? null : e.getStudent().getBirthDate().format(BIRTH));
            String enrolledTel = digits(e.getStudent().getPhone());

            if (decided.contains(e.getId())) {
                continue;
            }
            if (birthDigits != null && birthDigits.equals(enrolledBirth)) {
                matches.add(new EnrollmentMatch(e, MatchedBy.BIRTH));
            } else if (enrolledTel != null
                    && (enrolledTel.equals(studentTel) || enrolledTel.equals(parentTel))) {
                matches.add(new EnrollmentMatch(e, MatchedBy.TEL));
            }
        }
        return matches;
    }

    /**
     * 신규접수 저장을 막는다 — 같은 사람의 예약이 있으면.
     *
     * <p><b>예외에 후보 id 만 싣는다.</b> 오류 응답은 로그·모니터링에 그대로 남으므로
     * 이름·연락처를 넣지 않는다({@code BusinessException.data} 규약). 화면은 이 id 로
     * {@code GET /admin/admission-reservations/duplicates}를 불러 상세를 띄운다.
     *
     * @param confirmedNotDuplicate 담당자가 「다른 사람입니다」를 고른 경우. 그러면 통과한다
     */
    public void assertNotDuplicatedReservation(Long academyId, short year, String name,
                                               LocalDate birthDate, String tel,
                                               boolean confirmedNotDuplicate) {
        if (confirmedNotDuplicate) {
            // ⚠️ **여기서는 아직 등록 건이 없다** — 저장 직전에 막는 구조라 그렇다.
            //    쌍은 등록이 끝난 뒤 `recordDecision` 으로 적는다
            log.info("입학예약 중복 아님으로 확인 후 신규접수 진행: 지점={}, 연도={}", academyId, year);
            return;
        }
        List<Match> matches = findReservations(academyId, year, name,
                birthDate == null ? null : birthDate.format(BIRTH), tel);
        if (matches.isEmpty()) {
            return;
        }
        throw new BusinessException(ErrorCode.ADMISSION_DUPLICATE_SUSPECTED,
                ErrorCode.ADMISSION_DUPLICATE_SUSPECTED.getMessage(),
                matches.stream().map(m -> m.reservation().getId()).toList());
    }

    /**
     * 전환을 막는다 — 같은 사람이 이미 등록돼 있으면. 위와 대칭이다.
     *
     * @param confirmedNotDuplicate 「다른 사람입니다」를 고른 경우 통과한다
     */
    public void assertNotDuplicatedEnrollment(AdmissionReservation reservation,
                                              boolean confirmedNotDuplicate) {
        if (confirmedNotDuplicate) {
            log.info("기등록 학생과 다른 사람으로 확인 후 전환 진행: reservationId={}",
                    reservation.getId());
            // ★ 걸렸던 후보 전부를 「다른 사람」으로 적는다. 하나만 적으면 나머지가
            //   다음에 또 올라온다 — 담당자는 **그 화면 전체를 보고** 판단했다
            findEnrollments(reservation)
                    .forEach(m -> recordDecision(reservation, m.enrollment(),
                            AdmissionDuplicateDecision.DecidedOn.CONVERT));
            return;
        }
        List<EnrollmentMatch> matches = findEnrollments(reservation);
        if (matches.isEmpty()) {
            return;
        }
        throw new BusinessException(ErrorCode.ADMISSION_DUPLICATE_SUSPECTED,
                "같은 분이 이미 학생으로 등록돼 있습니다. 학번 %s"
                        .formatted(matches.get(0).enrollment().getStudentNo()),
                matches.stream().map(m -> m.enrollment().getId()).toList());
    }

    /**
     * 신규접수에서 내린 「다른 사람입니다」 판단을 남긴다 — <b>등록이 끝난 뒤</b>에 부른다.
     *
     * <p>막을 때와 <b>같은 조건으로 다시 찾아</b> 걸렸던 예약 전부를 적는다. 하나만 적으면
     * 나머지가 다음에 또 올라오는데, 담당자는 <b>그 화면 전체를 보고</b> 판단했다.
     */
    @Transactional
    public void recordAdmitDecisions(StudentEnrollment admitted, String name,
                                     LocalDate birthDate, String tel) {
        findReservations(admitted.getAcademy().getId(), admitted.getYear(), name,
                birthDate == null ? null : birthDate.format(BIRTH), tel)
                .forEach(m -> recordDecision(m.reservation(), admitted,
                        AdmissionDuplicateDecision.DecidedOn.ADMIT));
    }

    /**
     * 「다른 사람입니다」 판단을 남긴다.
     *
     * <p>★ <b>같은 쌍이 두 번 들어와도 한 행이다.</b> 두 번 눌렀다고 두 행이 되면
     * 「몇 번 판단했나」가 흐려지고, 유니크 제약에 걸려 <b>정상 저장까지 막힌다.</b>
     *
     * <p>⚠️ <b>이것이 실패해도 본 작업은 굴러가야 한다.</b> 판단 기록은 다음 번을
     * 편하게 하려는 보조 장치지, 등록·전환을 막을 이유가 아니다.
     */
    @Transactional
    public void recordDecision(AdmissionReservation reservation, StudentEnrollment enrollment,
                               AdmissionDuplicateDecision.DecidedOn decidedOn) {
        try {
            if (decisionRepository.existsByReservationIdAndEnrollmentIdAndDeletedFalse(
                    reservation.getId(), enrollment.getId())) {
                return;
            }
            decisionRepository.save(new AdmissionDuplicateDecision(
                    enrollment.getAcademy(), enrollment.getYear(),
                    reservation, enrollment, decidedOn));
        } catch (RuntimeException e) {
            log.warn("중복 아님 판단 기록 실패 — 본 작업은 계속한다: reservationId={}, enrollmentId={}",
                    reservation.getId(), enrollment.getId(), e);
        }
    }

    private MatchedBy matchedBy(AdmissionReservation r, String birthDigits, String telDigits) {
        if (birthDigits != null && birthDigits.equals(digits(r.getBirth()))) {
            return MatchedBy.BIRTH;
        }
        if (telDigits != null && (telDigits.equals(digits(r.getStudentTel()))
                || telDigits.equals(digits(r.getParentTel())))) {
            return MatchedBy.TEL;
        }
        return null;
    }

    /**
     * 숫자만 남긴다. 값이 없거나 숫자가 하나도 없으면 {@code null} —
     * <b>빈 문자열을 돌려주면 둘 다 비어 있는 값이 "일치"로 걸린다.</b>
     */
    private static String digits(String value) {
        if (value == null) {
            return null;
        }
        String digits = value.replaceAll("\\D", "");
        return digits.isEmpty() ? null : digits;
    }

    /** 무엇이 같아서 걸렸는가. 화면이 「생년월일이 같습니다」까지 보여줘야 사람이 판단할 수 있다. */
    public enum MatchedBy {
        BIRTH,
        TEL
    }

    public record Match(AdmissionReservation reservation, MatchedBy matchedBy) {
    }

    public record EnrollmentMatch(StudentEnrollment enrollment, MatchedBy matchedBy) {
    }
}
