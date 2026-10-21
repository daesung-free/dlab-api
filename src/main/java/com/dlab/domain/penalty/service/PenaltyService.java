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
@lombok.extern.slf4j.Slf4j
public class PenaltyService {

    private final PenaltyPointRepository pointRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final com.dlab.domain.user.repository.ClassAssignmentRepository classAssignmentRepository;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;
    private final com.dlab.domain.user.service.HomeroomScopeService homeroomScopeService;
    private final com.dlab.domain.facility.repository.SeatAssignmentRepository seatAssignmentRepository;
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
                                            String reason,
                                            java.time.LocalDate occurredOn) {
        List<StudentEnrollment> enrollments = enrollmentRepository.findAllById(enrollmentIds);
        if (enrollments.size() != enrollmentIds.size()) {
            throw new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND);
        }

        // ★ 발생일을 받는다. 어제 일을 오늘 넣는 경우가 실제로 있다 —
        //   현재 시각으로 박으면 그 건이 오늘 조회에 뜨고 어제 조회에서 빠진다.
        //   미래는 막는다: 아직 일어나지 않은 일에 벌점을 줄 수 없다
        java.time.Instant occurredAt = occurredOn == null
                ? java.time.Instant.now(clock)
                : occurredOn.atStartOfDay(clock.getZone()).toInstant();
        if (occurredOn != null && occurredOn.isAfter(java.time.LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "미래 일자로는 부여할 수 없습니다.");
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
                        null,
                        occurredAt))
                .toList();

        // created_by는 SecurityAuditorAware가 채운다 — 여기서 설정하지 않는다.
        return pointRepository.saveAll(granted);
    }

    /**
     * 학생 단위 누적 집계 (디랩 1021 피드백 2-5).
     *
     * <p>디랩 원문: <i>"전체 재원생의 현재 누적 상벌점 점수가 조회되면 좋을 것 같습니다.
     * <b>누적상점이 가장높은학생 순</b>으로 정렬되거나 <b>누적 벌점이 가장 높은학생 순</b>으로
     * 정렬되는 데이터가 필요합니다"</i>
     *
     * <h2>★★ 재원생 전체를 내린다 — 상벌점이 없는 학생도 0으로</h2>
     * 집계 쿼리는 상벌점이 <b>한 건이라도 있는 학생만</b> 돌려준다(그룹이 안 생긴다).
     * 그것만 내리면 <b>「상벌점 없는 학생」이 목록에서 사라지고</b>, 화면은 그걸
     * 「조회가 안 된다」로 읽는다. 재원생 목록을 기준으로 두고 0을 채운다.
     *
     * <h2>★ 상점 합과 벌점 합을 따로 낸다</h2>
     * 합계만 내면 <b>「상점 169 · 벌점 152 · 합 17」인 학생과 「상점 0 · 벌점 0」인 학생이
     * 같아 보인다.</b> 벌점은 음수로 저장되므로 <b>절댓값</b>으로 내린다 — 음수를 내보내면
     * 「벌점 높은 순」 정렬이 뒤집힌다.
     *
     * <p>⚠️ <b>기간을 걸지 않는다 — 누적이다.</b> 제적 기준 40점이 누적이라 기간 합계로는
     * 임계에 가까운 학생이 드러나지 않는다.
     *
     * <p>정렬은 부르는 쪽(컨트롤러)이 고른다 — 쿼리에서 정렬하면 0으로 채운 학생이
     * 섞이지 않는다.
     */
    @Transactional(readOnly = true)
    public List<StudentPenaltySummary> summaryByStudent(AuthPrincipal principal,
                                                        Long requestedAcademyId,
                                                        short year) {
        Long academyId = principal.resolveAcademyScope(requestedAcademyId);
        if (academyId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "지점을 지정해야 합니다.");
        }
        List<StudentEnrollment> enrollments =
                enrollmentRepository.findCurrentByAcademyAndYear(academyId, year);
        if (enrollments.isEmpty()) {
            return List.of();
        }
        var ids = enrollments.stream().map(StudentEnrollment::getId)
                .collect(java.util.stream.Collectors.toSet());

        // ★ 세 번만 조회한다 — 행마다 부르면 재원생 수만큼 쿼리가 나간다
        java.util.Map<Long, int[]> aggregates = new java.util.HashMap<>();
        for (Object[] row : pointRepository.aggregateByEnrollments(ids)) {
            aggregates.put((Long) row[0], new int[]{
                    ((Number) row[1]).intValue(),
                    ((Number) row[2]).intValue(),
                    ((Number) row[3]).intValue()});
        }
        java.util.Map<Long, String> classes = classAssignmentRepository
                .findActiveFixedByEnrollmentIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        a -> a.getEnrollment().getId(),
                        a -> a.getClassMaster().getName(), (x, y) -> x));
        java.util.Map<Long, String> seats = new java.util.HashMap<>();
        for (var assignment : seatAssignmentRepository.findActiveByEnrollmentIds(ids)) {
            seats.put(assignment.getEnrollment().getId(), assignment.getSeat().getSeatCd());
        }

        List<StudentPenaltySummary> result = new java.util.ArrayList<>();
        for (StudentEnrollment e : enrollments) {
            int[] agg = aggregates.getOrDefault(e.getId(), new int[]{0, 0, 0});
            result.add(new StudentPenaltySummary(
                    e.getId(), e.getStudentNo(), e.getStudent().getName(),
                    classes.get(e.getId()), seats.get(e.getId()),
                    e.getEnrollmentStatus(), agg[0], agg[1], agg[2]));
        }
        return result;
    }

    /**
     * @param merit    상점 합 (양수)
     * @param demerit  벌점 합 — ★ <b>절댓값</b>이다. 저장은 음수지만 화면이 음수를 받으면
     *                 「벌점 높은 순」 정렬이 뒤집힌다
     * @param total    순합계(상점 − 벌점). ⚠️ <b>제적 기준 40점은 벌점 누적</b>이라
     *                 이 값이 아니라 {@code demerit} 을 본다
     */
    public record StudentPenaltySummary(
            Long enrollmentId, String studentNo, String studentName,
            String className, String seatCd,
            com.dlab.domain.user.entity.EnrollmentStatus enrollmentStatus,
            int merit, int demerit, int total) {
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

    /**
     * 일괄 취소 (디랩 1021 피드백 2-7).
     *
     * <p>디랩 원문: <i>"체크 박스 체크 후에 <b>일괄 취소</b>가 가능하면 좋을 것 같습니다."</i>
     * 화면에 버튼은 있었는데 서버가 하나씩만 받았다.
     *
     * <h2>★ 한 건이 실패해도 나머지는 취소한다</h2>
     * 20건을 체크했는데 하나가 이미 취소된 건이라 전부 되돌아가면, 담당자는
     * <b>어느 것이 걸렸는지 모르는 채 다시 20건을 고르게</b> 된다.
     *
     * @return 취소한 id 와 실패한 id·사유
     */
    @Transactional
    public BulkRevokeResult revokeAll(AuthPrincipal principal, List<Long> penaltyPointIds) {
        List<Long> revoked = new java.util.ArrayList<>();
        java.util.Map<Long, String> failed = new java.util.LinkedHashMap<>();

        for (Long id : penaltyPointIds) {
            try {
                revoke(principal, id);
                revoked.add(id);
            } catch (BusinessException e) {
                // ★ 한 건의 실패가 나머지를 되돌리지 않게 한다.
                //   revoke 는 같은 트랜잭션이라 예외가 롤백 표시를 남기지 않는 BusinessException
                //   만 잡는다 — 그 밖의 예외는 그대로 올려 전체를 되돌린다
                failed.put(id, e.getMessage());
            }
        }
        log.info("상벌점 일괄 취소: 요청={}, 취소={}, 실패={}, 처리자={}",
                penaltyPointIds.size(), revoked.size(), failed.size(), principal.accountId());
        return new BulkRevokeResult(revoked, failed);
    }

    /**
     * @param failed id → 사유. ★ <b>화면에 사유를 그대로 보여줄 것</b> —
     *               「3건 실패」만 보여주면 담당자가 할 수 있는 일이 없다
     */
    public record BulkRevokeResult(List<Long> revoked, java.util.Map<Long, String> failed) {
    }

    /**
     * 일자·사유 수정 (디랩 1021 피드백 2-8).
     *
     * <p>★ <b>점수와 항목은 바꿀 수 없다</b>({@link PenaltyPoint#modify}).
     *
     * <p>⚠️ 일자를 바꾸면 그날 누적이 달라진다. 수정 이력은 {@code @Audited("상벌점")} 이
     * 자동으로 남긴다 — {@code occurredAt}·{@code reason} 에 {@code @AuditLabel} 이 달려 있어
     * 화면에 「발생 시각」·「사유」로 보인다.
     */
    @Transactional
    public PenaltyPoint modify(AuthPrincipal principal, Long penaltyPointId,
                               java.time.LocalDate occurredOn, String reason) {
        PenaltyPoint point = pointRepository.findById(penaltyPointId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "상벌점 내역을 찾을 수 없습니다."));
        validateAccessible(principal, point.getEnrollment());

        java.time.Instant at = occurredOn == null ? null
                : occurredOn.atStartOfDay(clock.getZone()).toInstant();
        point.modify(at, reason);
        log.info("상벌점 수정: id={}, 일자={}, 처리자={}",
                penaltyPointId, occurredOn, principal.accountId());
        return point;
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
                              List<PenaltySource> sources,
                              com.dlab.domain.user.entity.EnrollmentStatus enrollmentStatus,
                              String keyword, Long classId) {
        Long academyId = principal.requireAcademyScope(requestedAcademyId);

        java.time.ZoneId zone = clock.getZone();
        List<PenaltyPoint> points = pointRepository.search(
                academyId,
                from.atStartOfDay(zone).toInstant(),
                // 끝 날짜를 포함해야 한다 — 오늘 부여분이 오늘 조회에서 빠지면 확인이 안 된다
                to.plusDays(1).atStartOfDay(zone).toInstant(),
                category,
                sources == null || sources.isEmpty() ? null : sources,
                enrollmentStatus);

        // ★ 반 이름을 한 번에 푼다. 행마다 조회하면 목록 크기만큼 쿼리가 나간다 —
        //   반 필터도 여기서 같이 쓴다
        java.util.Map<Long, String> classes = classNamesOf(points);

        // ★ 담임은 맡은 학생만 본다. classId는 화면이 고르는 필터라 빼고 부르면 지점 전체가 나갔다.
        //   반이 아니라 학생으로 거른다 — 담임 예외 지정된 학생이 새 담임에게 보여야 한다
        var studentFilter = homeroomScopeService.resolveStudentFilter(
                principal, (short) from.getYear(), classId);
        if (studentFilter.blocksEverything()) {
            return new PenaltyBoard(List.of(), 0, 0, 0L, java.util.Map.of(), classes,
                    java.util.Map.of(), java.util.Map.of());
        }

        List<PenaltyPoint> filtered = points.stream()
                .filter(p -> matchesKeyword(p, keyword))
                .filter(p -> studentFilter.matches(p.getEnrollment().getId()))
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

        return new PenaltyBoard(filtered, plus, -minus, auto,
                granterNames(filtered), classes,
                cumulativePointsOf(filtered), seatCodesOf(filtered));
    }

    /**
     * 부여자 계정 ID → 이름.
     *
     * <p><b>한 번에 조회한다.</b> 행마다 부르면 목록 크기만큼 쿼리가 나간다.
     *
     * <p>배치·스케줄러가 부여한 건은 시스템 계정({@code 0})이라 조회되지 않는다 —
     * 화면이 {@code null}을 보고 "자동"으로 표시하면 된다.
     */
    /**
     * 등록 건 → <b>누적</b> 점수.
     *
     * <p>★ <b>한 번에 받는다.</b> 행마다 조회하면 200명이면 쿼리가 200번 나간다.
     * 같은 학생의 행이 여러 개여도 조회는 학생 수만큼이다.
     *
     * <p>⚠️ <b>기간을 걸지 않는다.</b> 「누적 38점 (이번 달 +3)」에서 앞 숫자이고,
     * 제적 기준 40점이 그 누적이다 — 기간 합계로 바꾸면 경고가 늦게 뜬다.
     */
    private java.util.Map<Long, Integer> cumulativePointsOf(List<PenaltyPoint> rows) {
        var ids = rows.stream().map(p -> p.getEnrollment().getId())
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<Long, Integer> totals = new java.util.HashMap<>();
        for (Object[] row : pointRepository.sumPointsByEnrollments(ids)) {
            totals.put((Long) row[0], ((Number) row[1]).intValue());
        }
        return totals;
    }

    /** 등록 건 → 좌석번호. 좌석번호 조회·정렬에 쓴다(피드백 5-7). 미배정이면 없다. */
    private java.util.Map<Long, String> seatCodesOf(List<PenaltyPoint> rows) {
        var ids = rows.stream().map(p -> p.getEnrollment().getId())
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        // 이미 있는 묶음 조회를 쓴다 — 좌석을 fetch 해 오므로 추가 쿼리가 없다
        java.util.Map<Long, String> seats = new java.util.HashMap<>();
        for (var assignment : seatAssignmentRepository.findActiveByEnrollmentIds(ids)) {
            seats.put(assignment.getEnrollment().getId(), assignment.getSeat().getSeatCd());
        }
        return seats;
    }

    private java.util.Map<Long, String> granterNames(List<PenaltyPoint> rows) {
        java.util.Set<Long> ids = rows.stream()
                .map(PenaltyPoint::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (ids.isEmpty()) {
            return java.util.Map.of();
        }
        return accountRepository.findDisplayNames(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        r -> ((Number) r[0]).longValue(),
                        r -> (String) r[1],
                        (a, b) -> a));
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

    /**
     * 등록 건 ID → 반 이름.
     *
     * <p>화면 '반' 컬럼이 이걸 찍고, 반 필터도 같은 결과를 쓴다. <b>한 번에 조회한다</b> —
     * 행마다 부르면 목록 크기만큼 쿼리가 나간다.
     */
    private java.util.Map<Long, String> classNamesOf(List<PenaltyPoint> rows) {
        if (rows.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Set<Long> ids = rows.stream()
                .map(p -> p.getEnrollment().getId())
                .collect(java.util.stream.Collectors.toSet());
        return classAssignmentRepository.findActiveFixedByEnrollmentIds(ids).stream()
                .collect(java.util.stream.Collectors.toMap(
                        a -> a.getEnrollment().getId(),
                        a -> a.getClassMaster().getName(),
                        (x, y) -> x));
    }

    // ★ 반 필터를 행마다 조회하던 matchesClass 는 뺐다 — 위 inClass 집합이 같은 일을
    //   쿼리 한 번으로 한다. 둘 다 두면 목록 크기만큼 쿼리가 나가는 쪽이 남는다.


    /**
     * @param plusTotal  상점 합계(양수)
     * @param minusTotal 벌점 합계(<b>음수로 표시</b>). 화면이 "-12"처럼 그대로 찍는다
     * @param autoCount  자동 부여 건수. 규칙(I-5) 확정 전이라 보통 0이다
     */
    /**
     * @param granterNames 부여자 계정 ID → 이름. 배치 부여분은 없다
     * @param classNames   등록 건 ID → 반 이름. 미배정이면 없다
     */
    /**
     * @param cumulativePoints 등록 건 ID → <b>누적</b> 점수. 기간 합계가 아니다 —
     *                         제적 기준 40점이 누적이라, 기간만 보이면 학부모가 오인한다
     * @param seatCodes        등록 건 ID → 좌석번호. 미배정이면 없다(피드백 5-7)
     */
    public record PenaltyBoard(List<PenaltyPoint> rows, int plusTotal, int minusTotal,
                               long autoCount, java.util.Map<Long, String> granterNames,
                               java.util.Map<Long, String> classNames,
                               java.util.Map<Long, Integer> cumulativePoints,
                               java.util.Map<Long, String> seatCodes) {
    }
}
