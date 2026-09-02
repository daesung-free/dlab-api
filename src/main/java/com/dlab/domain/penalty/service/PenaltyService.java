package com.dlab.domain.penalty.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.penalty.entity.PenaltyItem;
import com.dlab.domain.penalty.entity.PenaltyPoint;
import com.dlab.domain.penalty.entity.PenaltyCategory;
import com.dlab.domain.penalty.entity.PenaltySource;
import com.dlab.domain.penalty.repository.PenaltyPointRepository;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상벌점 수기 부여.
 *
 * <p>DSA 실사에서 확인된 흐름은 <b>"조건검색 → 학생 선택 → 항목 선택 → 일괄 부여"</b>다.
 * 개별 폼으로 한 명씩 넣는 구조가 아니다.
 *
 * <p><b>점수는 조정하지 않는다.</b> 항목({@code penalty_item.point_value})의 값을 그대로
 * 복사한다 — 부여 시 임의 조정을 허용하면 같은 사유에 사람마다 다른 점수가 붙어
 * 이의 제기가 들어왔을 때 근거를 댈 수 없다.
 */
@Service
@RequiredArgsConstructor
public class PenaltyService {

    private final PenaltyPointRepository pointRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final com.dlab.domain.user.repository.ClassAssignmentRepository classAssignmentRepository;
    private final java.time.Clock clock;

    /**
     * 여러 학생에게 같은 항목을 일괄 부여한다.
     *
     * <p>수기 부여는 멱등키가 없다({@code null}). 같은 사유로 두 번 부여하는 게
     * 실제로 있을 수 있어서다(같은 날 지각 2회 등) — 자동부여와 달리 사람이 판단한다.
     */
    @Transactional
    public List<PenaltyPoint> grantManually(AuthPrincipal principal,
                                            List<Long> enrollmentIds,
                                            PenaltyItem item,
                                            String reason) {
        List<StudentEnrollment> enrollments = enrollmentRepository.findAllById(enrollmentIds);
        if (enrollments.size() != enrollmentIds.size()) {
            throw new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND);
        }

        List<PenaltyPoint> granted = enrollments.stream()
                .peek(e -> validateAccessible(principal, e))
                .map(e -> new PenaltyPoint(
                        e.getAcademy(),
                        e,
                        item,
                        // 항목 점수 그대로. 조정 없음.
                        item.getPointValue(),
                        reason == null || reason.isBlank() ? item.getItemName() : reason,
                        PenaltySource.MANUAL,
                        null))
                .toList();

        // created_by는 SecurityAuditorAware가 채운다 — 여기서 설정하지 않는다.
        return pointRepository.saveAll(granted);
    }

    /** soft delete. 부여를 취소해도 이력은 남겨야 이의 제기 대응이 된다. */
    @Transactional
    public void revoke(AuthPrincipal principal, Long penaltyPointId) {
        PenaltyPoint point = pointRepository.findById(penaltyPointId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "상벌점 내역을 찾을 수 없습니다."));
        validateAccessible(principal, point.getEnrollment());
        point.markDeleted();
    }

    @Transactional(readOnly = true)
    public List<PenaltyPoint> findByEnrollment(AuthPrincipal principal, Long enrollmentId) {
        List<PenaltyPoint> points = pointRepository.findByEnrollment(enrollmentId);
        points.stream().findFirst()
                .ifPresent(p -> validateAccessible(principal, p.getEnrollment()));
        return points;
    }

    /** 앱 홈 3지표의 "벌점 현황". */
    @Transactional(readOnly = true)
    public int totalPoints(Long enrollmentId) {
        return pointRepository.sumPointsByEnrollment(enrollmentId);
    }

    private void validateAccessible(AuthPrincipal principal, StudentEnrollment enrollment) {
        if (!principal.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
    }

    /**
     * 관리자 목록 (F-4.1-2).
     *
     * <p>화면 상단 합계가 <b>조회 조건 기준</b>이라(목업 "조회 조건 기준") 필터를 적용한
     * 결과로 합산한다 — 전체 합계를 내리면 필터를 걸어도 숫자가 안 바뀐다.
     *
     * @param requestedAcademyId 조회할 지점. <b>비우면 내 지점</b>이고,
     *                           전 지점 권한자는 지정해야 한다
     */
    @Transactional(readOnly = true)
    public PenaltyBoard board(AuthPrincipal principal, Long requestedAcademyId,
                              java.time.LocalDate from,
                              java.time.LocalDate to, PenaltyCategory category,
                              PenaltySource source, String keyword, Long classId) {
        Long academyId = principal.requireAcademyScope(requestedAcademyId);

        java.time.ZoneId zone = clock.getZone();
        List<PenaltyPoint> points = pointRepository.search(
                academyId,
                from.atStartOfDay(zone).toInstant(),
                // 끝 날짜를 포함해야 한다 — 오늘 부여분이 오늘 조회에서 빠지면 확인이 안 된다
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                category, source);

        List<PenaltyPoint> filtered = points.stream()
                .filter(p -> matchesKeyword(p, keyword))
                .filter(p -> matchesClass(p, classId))
                .toList();

        int plus = filtered.stream()
                .filter(p -> p.getPenaltyItem().getCategory() == PenaltyCategory.MERIT)
                .mapToInt(p -> Math.abs(p.getPoints())).sum();
        // 벌점 점수는 양수로 저장하고 상/벌은 category로 구분한다.
        // 혹시 음수로 들어온 값이 있어도 부호가 뒤집히지 않게 절댓값으로 센다
        int minus = filtered.stream()
                .filter(p -> p.getPenaltyItem().getCategory() == PenaltyCategory.DEMERIT)
                .mapToInt(p -> Math.abs(p.getPoints())).sum();
        long auto = filtered.stream()
                .filter(p -> p.getSource() != PenaltySource.MANUAL).count();

        return new PenaltyBoard(filtered, plus, -minus, auto);
    }

    private boolean matchesKeyword(PenaltyPoint p, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return true;
        }
        String kw = keyword.trim();
        String name = p.getEnrollment().getStudent().getName();
        String studentNo = p.getEnrollment().getStudentNo();
        return (name != null && name.contains(kw))
                || (studentNo != null && studentNo.contains(kw));
    }

    private boolean matchesClass(PenaltyPoint p, Long classId) {
        if (classId == null) {
            return true;
        }
        return classAssignmentRepository
                .findActiveFixedByEnrollmentId(p.getEnrollment().getId())
                .map(a -> a.getClassMaster().getId().equals(classId))
                .orElse(false);
    }

    /**
     * @param plusTotal  상점 합계(양수)
     * @param minusTotal 벌점 합계(<b>음수로 표시</b>). 화면이 "-12"처럼 그대로 찍는다
     * @param autoCount  자동 부여 건수. 규칙(I-5) 확정 전이라 보통 0이다
     */
    public record PenaltyBoard(List<PenaltyPoint> rows, int plusTotal, int minusTotal, long autoCount) {
    }
}
