package com.dlab.domain.admission.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.admission.entity.*;
import com.dlab.domain.admission.repository.AdmissionResultRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실적 관리 = 수시/정시 지원대학과 결과 (F-4.10-6).
 *
 * <h2>개수 제한은 서버가 지킨다</h2>
 * 수시 6개·정시 3개다. 화면에서만 막으면 <b>주소를 직접 부르거나 두 창에서 동시에 넣을 때
 * 통과한다.</b>
 *
 * <h2>대학·학과 마스터를 두지 않는다</h2>
 * 전국 대학 전형은 매년 바뀌어 갱신 부담이 크다(§4). <b>쌓인 값에서 자동완성을 만든다</b> —
 * 마스터가 없어도 지금 쓸 수 있고, 쓸수록 목록이 정확해진다.
 */
@Service
@RequiredArgsConstructor
public class AdmissionResultService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AdmissionResultRepository resultRepository;
    private final StudentEnrollmentRepository enrollmentRepository;

    @Transactional(readOnly = true)
    public List<AdmissionResult> findByStudent(AuthPrincipal me, Long enrollmentId) {
        requireEnrollment(me, enrollmentId);
        return resultRepository.findByEnrollmentId(enrollmentId);
    }

    @Transactional
    public AdmissionResult create(AuthPrincipal me, Long enrollmentId, AdmissionType type,
                                  String universityName, String departmentName, String trackName,
                                  AdmissionResultStatus result, AdmissionSource source,
                                  String memo) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);

        long already = resultRepository.countByType(enrollmentId, type);
        if (already >= type.limit()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "%s는 %d개까지 등록합니다. 지우고 다시 넣어 주세요."
                            .formatted(type.displayName(), type.limit()));
        }
        return resultRepository.save(new AdmissionResult(enrollment, type,
                universityName, departmentName, trackName, result, source, memo));
    }

    @Transactional
    public AdmissionResult update(AuthPrincipal me, Long resultId, AdmissionType type,
                                  String universityName, String departmentName, String trackName,
                                  AdmissionResultStatus result, String memo) {
        AdmissionResult target = require(me, resultId);
        // 수시 ↔ 정시로 옮기면 옮겨 가는 쪽 정원을 넘을 수 있다
        if (type != null && type != target.getAdmissionType()
                && resultRepository.countByType(target.getEnrollment().getId(), type) >= type.limit()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "%s는 %d개까지 등록합니다.".formatted(type.displayName(), type.limit()));
        }
        target.update(type, universityName, departmentName, trackName, result, memo);
        return target;
    }

    /** 삭제는 soft delete 다 — 지난 지원 이력이 실적 집계의 근거로 남아야 한다. */
    @Transactional
    public void delete(AuthPrincipal me, Long resultId) {
        require(me, resultId).markDeleted();
    }

    /**
     * 기간별 집계.
     *
     * <p>★ <b>발표 전(`PENDING`)을 불합격에 섞지 않는다.</b> 섞으면 발표가 안 난 지원까지
     * 실패로 잡혀 합격률이 실제보다 낮게 나온다.
     *
     * <p>합격률은 <b>등록포기를 합격으로</b> 센다 — 붙은 것은 사실이고, 등록 여부는
     * 등록률이 따로 답한다.
     */
    @Transactional(readOnly = true)
    public Statistics statistics(AuthPrincipal me, Long academyId, short year,
                                 LocalDate from, LocalDate to) {
        Long scope = me.requireAcademyScope(academyId);
        List<AdmissionResult> rows = resultRepository.findInPeriod(scope, year,
                from.atStartOfDay(SEOUL).toInstant(), to.plusDays(1).atStartOfDay(SEOUL).toInstant());

        Map<AdmissionType, Long> byType = rows.stream()
                .collect(Collectors.groupingBy(AdmissionResult::getAdmissionType,
                        Collectors.counting()));
        Map<AdmissionResultStatus, Long> byResult = rows.stream()
                .collect(Collectors.groupingBy(AdmissionResult::getResult, Collectors.counting()));
        Map<String, Long> byUniversity = rows.stream()
                .filter(r -> r.getResult().isPass())
                .collect(Collectors.groupingBy(AdmissionResult::getUniversityName,
                        Collectors.counting()));

        long decided = rows.stream()
                .filter(r -> r.getResult() != AdmissionResultStatus.PENDING).count();
        long passed = rows.stream().filter(r -> r.getResult().isPass()).count();

        return new Statistics(rows.size(),
                byType.getOrDefault(AdmissionType.EARLY, 0L),
                byType.getOrDefault(AdmissionType.REGULAR, 0L),
                decided, passed, byResult, byUniversity);
    }

    /**
     * 자동완성 후보.
     *
     * <p>★ <b>없으면 빈 목록이다. 오류가 아니다.</b> 첫 해에는 아무것도 안 쌓여 있고,
     * 그때도 직접 입력으로 쓸 수 있어야 한다.
     */
    @Transactional(readOnly = true)
    public Suggestions suggest(String university, String keyword) {
        String prefix = keyword == null ? "" : keyword.trim();
        return new Suggestions(
                resultRepository.suggestUniversities(prefix).stream().limit(20).toList(),
                resultRepository.suggestDepartments(
                        university == null || university.isBlank() ? null : university, prefix)
                        .stream().limit(20).toList());
    }

    private AdmissionResult require(AuthPrincipal me, Long resultId) {
        AdmissionResult result = resultRepository.findById(resultId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "실적을 찾을 수 없습니다."));
        me.requireAcademyScope(result.getAcademy().getId());
        return result;
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        // 검사가 없으면 enrollmentId 만 바꿔 남의 지점 학생 실적이 열린다
        me.requireAcademyScope(enrollment.getAcademy().getId());
        return enrollment;
    }

    /**
     * @param decided 발표가 난 건수. 합격률의 분모다 — 전체로 나누면 발표 전까지 섞인다
     */
    public record Suggestions(List<String> universities, List<String> departments) {
    }

    public record Statistics(int total, long early, long regular,
                             long decided, long passed,
                             Map<AdmissionResultStatus, Long> byResult,
                             Map<String, Long> passedByUniversity) {
    }
}
