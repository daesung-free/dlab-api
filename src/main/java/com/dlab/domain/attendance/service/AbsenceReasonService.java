package com.dlab.domain.attendance.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApprovalRequest;
import com.dlab.domain.approval.entity.ApprovalStatus;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.service.ApprovalService;
import com.dlab.domain.attendance.entity.AbsenceReason;
import com.dlab.domain.attendance.entity.AbsenceReasonType;
import com.dlab.domain.penalty.entity.PenaltyTriggerType;
import com.dlab.domain.attendance.entity.AbsenceSubmitterType;
import com.dlab.domain.attendance.repository.AbsenceReasonRepository;
import com.dlab.domain.user.entity.ClassAssignment;
import com.dlab.domain.user.entity.StudentEnrollment;
import com.dlab.domain.user.repository.ClassAssignmentRepository;
import com.dlab.domain.user.repository.StudentEnrollmentRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사유 신청 관리 (F-4.1-6).
 *
 * <p>DSA는 관리자가 직접 등록·수정하는 단일 구조였다. <b>앱 실시간 제출 →
 * 승인 주체별 라우팅</b>이 신규다. 관리자 직접 등록도 병행한다(시트 명시).
 *
 * <h2>승인·반려는 여기 없다</h2>
 * 이미 {@code ApprovalService}와 {@code /api/v1/admin/approvals}가 한다.
 * 목록에 {@code approvalRequestId}를 실어 보내면 화면이 그 API를 그대로 부르면 된다 —
 * <b>승인 로직을 두 벌 만들면 타임아웃·에스컬레이션 처리가 갈린다.</b>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AbsenceReasonService {

    /** 학부모 미응답 에스컬레이션 후보 기준. 화면 통계가 "2시간+"로 표시한다. */
    private static final Duration ESCALATION_THRESHOLD = Duration.ofHours(2);

    private final AbsenceReasonRepository absenceReasonRepository;
    private final StudentEnrollmentRepository enrollmentRepository;
    private final ClassAssignmentRepository classAssignmentRepository;
    private final com.dlab.domain.attendance.repository.AbsenceReasonCategoryRepository categoryRepository;
    private final ApprovalService approvalService;
    private final com.dlab.domain.user.service.HomeroomScopeService homeroomScopeService;
    private final com.dlab.domain.penalty.service.PenaltyRuleEngine penaltyRuleEngine;
    private final com.dlab.domain.user.repository.AccountRepository accountRepository;
    private final Clock clock;

    /**
     * 관리자 직접 등록.
     *
     * <p><b>등록해도 자동 승인은 아니다.</b> 승인 라우팅을 그대로 탄다 —
     * 관리자가 넣었다고 건너뛰면 학부모 승인이 필요한 유형(결석·조퇴)에서
     * 학부모가 모르는 사이에 처리된다.
     *
     * @param startTime 외출·조퇴 시작. 결석·지각은 종일이라 {@code null}
     * @param endTime   외출 종료. 조퇴는 복귀가 없어 {@code null}
     */
    @Transactional
    public AbsenceReason register(AuthPrincipal me, Long enrollmentId, LocalDate date,
                                  AbsenceReasonType type, String reasonText,
                                  LocalTime startTime, LocalTime endTime, Long categoryId) {
        StudentEnrollment enrollment = requireEnrollment(me, enrollmentId);
        validatePeriod(type, startTime, endTime);

        AbsenceReason reason = absenceReasonRepository.save(new AbsenceReason(
                enrollment.getAcademy(), enrollment, date, type, reasonText, startTime, endTime));
        applyCategory(reason, categoryId, enrollment);

        ApprovalRequest approval = approvalService.create(enrollment, RequestType.ABSENCE_REASON);
        reason.linkApproval(approval);

        log.info("사유신청 등록(관리자): enrollmentId={}, 유형={}, 일자={}", enrollmentId, type, date);
        return reason;
    }

    /**
     * 앱 제출 — <b>학생 본인</b>.
     *
     * <p>관리자 등록({@link #register})과 <b>같은 라우팅을 탄다.</b> 제출 경로만 다르고
     * 승인 주체·타임아웃·에스컬레이션은 하나의 엔진이 처리한다.
     *
     * <p><b>지점 검사를 하지 않는다</b> — 관리자 경로와 달리 남의 등록 건을 지목할 방법이
     * 없다. 컨트롤러가 로그인한 학생의 등록 건을 직접 넘긴다.
     *
     * <p><b>날짜는 과거·미래를 모두 받는다.</b> 사전 제출(내일 결석 예고)은 미등원 알림
     * 제외에 필요하고, 사후 제출(아파서 결석한 다음 날)은 실제로 가장 흔한 흐름이다.
     */
    @Transactional
    public AbsenceReason submitByStudent(Long enrollmentId, LocalDate date,
                                         AbsenceReasonType type, String reasonText,
                                         LocalTime startTime, LocalTime endTime, Long categoryId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        validatePeriod(type, startTime, endTime);
        rejectDuplicate(enrollmentId, date, type);

        AbsenceReason reason = absenceReasonRepository.save(new AbsenceReason(
                enrollment.getAcademy(), enrollment, date, type, reasonText, startTime, endTime));
        applyCategory(reason, categoryId, enrollment);

        reason.recordSubmitter(AbsenceSubmitterType.STUDENT, null);

        ApprovalRequest approval = approvalService.create(enrollment, RequestType.ABSENCE_REASON);
        reason.linkApproval(approval);

        log.info("사유신청 제출(앱): enrollmentId={}, 유형={}, 일자={}", enrollmentId, type, date);
        return reason;
    }

    /**
     * ★★ <b>학부모가</b> 앱에서 사유를 낸다 (디랩 1031 ③).
     *
     * <h2>★ 왜 열었는가 — 받은 사람이 할 수 없는 일을 안내하고 있었다</h2>
     * 결석 안내 알림톡은 <b>보호자에게만</b> 가는데(UM_8236) 문구가
     * <b>「사유가 있으시면 어플에서 사유 입력을 부탁드립니다」</b> 다. 그런데 지금까지
     * <b>학생만 낼 수 있었다.</b>
     *
     * <h2>★★ 우선 승인자가 「부모」면 <b>제출 즉시 승인</b>이다</h2>
     * <b>낸 것이 곧 학부모 확인</b>이다(디랩 1031 §2 표). 승인을 또 받게 하면
     * <b>자기가 낸 것을 자기가 승인</b>하는 절차가 되어 한 단계가 형식만 남는다.
     *
     * <p>⚠️ <b>승인 요청 행을 「만들지 않는」 것이 아니다.</b> 만들고 <b>그 자리에서
     * 승인</b>한다 — 그러지 않으면 승인 보드에 아무것도 남지 않아
     * <b>「승인 요청이 왜 안 보이지」에 답할 수 없다</b>(프런트 지적).
     * {@code resolver_account_id} 에 <b>그 학부모가 승인자로 박힌다.</b>
     *
     * <p>★ <b>「담임」이면 그대로 담임에게 간다</b> — 학생이 낸 것과 같다.
     *
     * <p>★ <b>벌점도 같은 경로를 탄다</b> — 승인될 때 붙는다(디랩 1029 4-1). 제출 즉시
     * 승인이어도 예외가 아니다. 그 근거는 입학동의서 9항(<b>「벌점부여에 동의」</b>)이고,
     * 9-4 가 <b>「모두 학부모님께 확인 절차를 거친 후」</b> 다 — <b>학부모가 확인하고,
     * 그 확인에 벌점이 붙는 것에 학부모가 이미 동의한 구조다.</b>
     *
     * @param guardianAccount 낸 학부모 계정. ★ 승인자로도 기록된다
     */
    @Transactional
    public AbsenceReason submitByGuardian(StudentEnrollment enrollment,
                                          com.dlab.domain.user.entity.Account guardianAccount,
                                          LocalDate date, AbsenceReasonType type,
                                          String reasonText, LocalTime startTime,
                                          LocalTime endTime, Long categoryId) {
        validatePeriod(type, startTime, endTime);
        rejectDuplicate(enrollment.getId(), date, type);

        AbsenceReason reason = absenceReasonRepository.save(new AbsenceReason(
                enrollment.getAcademy(), enrollment, date, type, reasonText,
                startTime, endTime));
        applyCategory(reason, categoryId, enrollment);
        reason.recordSubmitter(AbsenceSubmitterType.GUARDIAN, guardianAccount);

        ApprovalRequest approval = approvalService.create(
                enrollment, RequestType.ABSENCE_REASON,
                // ★ 즉시 승인될 건에는 ★ 「승인해주세요」 알림을 보내지 않는다 —
                //   ⚠️ 자기가 낸 신청의 승인 요청을 자기가 받는 꼴이 된다
                !approvalService.isParentPrimary(enrollment, RequestType.ABSENCE_REASON));
        reason.linkApproval(approval);

        if (approvalService.isParentPrimary(enrollment, RequestType.ABSENCE_REASON)) {
            // ★ 낸 것이 곧 학부모 확인이다 — 그 학부모가 승인자로 박힌다
            approvalService.approve(approval.getId(), guardianAccount.getId());
            log.info("사유신청 제출 즉시 승인(학부모): enrollmentId={}, 유형={}, 일자={}",
                    enrollment.getId(), type, date);
        } else {
            log.info("사유신청 제출(학부모, 담임 승인 대기): enrollmentId={}, 유형={}, 일자={}",
                    enrollment.getId(), type, date);
        }
        return reason;
    }

    // ── 후속 처리 (디랩 1029 4-3) ────────────────────────────────

    /**
     * ★★ <b>증빙 확인</b> — 직원이 체크하면 <b>그 신청으로 붙은 자동 벌점을 거둔다</b>
     * (1029 4-3 #36).
     *
     * <p>시트 원문: <b>「사유외출 및 조퇴 시 증빙영수증 제출할 경우 직원 확인 체크 시
     * 기존 부여된 벌점 자동 삭제」</b>
     *
     * <h2>★ 0점 항목을 기록으로 남긴다</h2>
     * 「디랩 인정사유 및 병원영수증 제출」은 <b>0점</b>이다 — 점수가 아니라
     * <b>「이런 일이 있었다」는 기록</b>이다. ⚠️ 거두기만 하면 <b>왜 사라졌는지</b>가
     * 남지 않는다.
     *
     * <h2>⚠️ 신청 종류로 막지 않는다</h2>
     * 시트는 「사유외출 및 조퇴」만 적었지만, 사유 결석·지각에 열지는
     * <b>{@code penalty_rule} 행으로 정한다</b>(1029 4-3 지침) — 화면에서 막으면
     * 디랩이 범위를 넓힐 때 코드를 고쳐야 한다.
     *
     * <p>★ <b>두 번 누르면 거부한다</b> — 두 번째 호출이 0점 기록을 또 남기면
     * 「몇 번 확인했나」가 의미를 잃는다(멱등키가 같아 벌점은 안 늘지만 혼란스럽다).
     */
    @Transactional
    public AbsenceReason confirmEvidence(AuthPrincipal me, Long reasonId) {
        AbsenceReason reason = requireScoped(me, reasonId);
        if (reason.getEvidenceConfirmedAt() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 증빙을 확인했습니다.");
        }
        reason.confirmEvidence(staff(me), Instant.now(clock));

        // ★ 「증빙 확인」 트리거로 돈다. 거둘 대상과 기록 항목은 ★ 전부 규칙 데이터다
        penaltyRuleEngine.apply(reason.getEnrollment(), PenaltyTriggerType.APPROVAL,
                EVIDENCE_CONFIRMED, reason.getAttendanceDate());

        log.info("사유신청 증빙 확인: reasonId={}, 학생={}", reasonId,
                reason.getEnrollment().getId());
        return reason;
    }

    /**
     * ★★ <b>미진행</b> — 승인됐는데 실제로 하지 않은 경우 (1029 4-3 #10).
     *
     * <h2>⚠️ 10/21 결론이 뒤집힌 자리다</h2>
     * 그때는 <b>「외출 신청 후 안 나가면 취소할 벌점이 없다 — 개발할 것 없음」</b> 으로
     * 디랩에 회신했다. <b>승인 시 부여로 바뀌면서</b>(1029 4-1) 정리 경로가 필요해졌다.
     *
     * <h2>★★ 9-7 회신을 기다리지 않는다 — 데이터로 흡수된다</h2>
     * 미확정은 <b>「-1 을 취소하고 0점 기록만」 vs 「-1 을 두고 0점 기록을 더함」</b>
     * 인데, ★ 그 차이는 <b>{@code penalty_rule.supersedes_rule_id}</b> 하나다 —
     * 「미진행」 규칙에 <b>대체 대상을 지정하면 전자, 비우면 후자</b>다.
     * ★ 회신이 와도 <b>코드가 아니라 행을 고친다.</b>
     *
     * <p>★ <b>버튼 하나로 끝난다</b>(1029 4-3) — ⚠️ 항목을 손으로 찾아 넣게 하면
     * <b>빠진다.</b>
     */
    @Transactional
    public AbsenceReason markNotExecuted(AuthPrincipal me, Long reasonId) {
        AbsenceReason reason = requireScoped(me, reasonId);
        if (reason.getNotExecutedAt() != null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "이미 미진행으로 표시했습니다.");
        }
        reason.markNotExecuted(staff(me), Instant.now(clock));

        penaltyRuleEngine.apply(reason.getEnrollment(), PenaltyTriggerType.APPROVAL,
                NOT_EXECUTED, reason.getAttendanceDate());

        log.info("사유신청 미진행 표시: reasonId={}, 학생={}", reasonId,
                reason.getEnrollment().getId());
        return reason;
    }

    /**
     * ★ 후속 처리 트리거 조건값.
     *
     * <p>⚠️ {@code AbsenceReasonType} 값과 <b>같은 자리에 들어간다</b> — 사유 종류와
     * 겹치지 않는 이름을 쓴다. 겹치면 <b>승인 벌점 규칙이 후속 처리에도 걸린다.</b>
     */
    public static final String EVIDENCE_CONFIRMED =
            com.dlab.domain.penalty.entity.TriggerCondition.EVIDENCE_CONFIRMED;
    public static final String NOT_EXECUTED =
            com.dlab.domain.penalty.entity.TriggerCondition.NOT_EXECUTED;

    /**
     * ★ 신청을 ★ 권한 범위 안에서 찾는다.
     *
     * <p>⚠️ {@code reasonId} 를 요청으로 받으므로 <b>값을 바꿔 보낼 수 있다</b> —
     * 지점 검사 없이는 <b>남의 지점 신청을 후속 처리할 수 있다.</b>
     */
    /**
     * ★ 승인 요청으로 사유 신청을 찾는다 — <b>후속 처리 버튼이 승인 보드에서 눌린다.</b>
     *
     * <p>⚠️ 한 승인 요청에 사유가 여럿일 수 있어(정기 신청) <b>첫 건</b>을 돌려준다 —
     * 화면이 보드에서 바로 누를 수 있어야 한다.
     */
    public AbsenceReason findByApproval(Long approvalRequestId) {
        return absenceReasonRepository.findByApprovalRequestId(approvalRequestId).stream()
                .filter(r -> !r.isDeleted())
                .findFirst()
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "사유 신청을 찾을 수 없습니다."));
    }

    private AbsenceReason requireScoped(AuthPrincipal me, Long reasonId) {
        AbsenceReason reason = absenceReasonRepository.findById(reasonId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "사유 신청을 찾을 수 없습니다."));
        me.requireAcademyScope(reason.getAcademy().getId());
        return reason;
    }

    private com.dlab.domain.user.entity.Account staff(AuthPrincipal me) {
        return accountRepository.findById(me.accountId())
                .orElseThrow(() -> new BusinessException(ErrorCode.UNAUTHORIZED));
    }

    /**
     * 카테고리 연결.
     *
     * <p><b>비워도 된다</b> — 카테고리가 하나도 등록되지 않은 상태에서도 사유 제출이
     * 되어야 한다. 다만 보냈다면 <b>그 지점에서 쓸 수 있는 것</b>인지는 확인한다.
     * 안 그러면 id 를 바꿔 다른 지점 카테고리가 붙는다.
     */
    private void applyCategory(AbsenceReason reason, Long categoryId,
                               StudentEnrollment enrollment) {
        if (categoryId == null) {
            return;
        }
        var category = categoryRepository.findActiveById(categoryId)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_REQUEST,
                        "사유 카테고리를 찾을 수 없습니다."));
        boolean usable = category.getAcademy() == null
                || category.getAcademy().getId().equals(enrollment.getAcademy().getId());
        if (!usable) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        reason.changeCategory(category);
    }

    /**
     * 앱 취소 — <b>승인 전까지만</b>.
     *
     * <p>승인·반려된 건은 이력이라 지우지 않는다. 되돌릴 일이면 관리자가 정정한다 —
     * 학생이 지울 수 있으면 <b>"승인받고 나서 없던 일로 만드는" 경로</b>가 열린다.
     *
     * <p>승인 요청 취소는 {@code ApprovalService}가 조건부 UPDATE로 처리한다.
     * 학생이 취소하는 순간 학부모가 승인할 수 있어, 먼저 도착한 쪽만 성공해야 한다.
     */
    @Transactional
    public void cancelByStudent(Long enrollmentId, Long reasonId) {
        AbsenceReason reason = absenceReasonRepository.findById(reasonId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ABSENCE_REASON_NOT_FOUND));

        if (!reason.getEnrollment().getId().equals(enrollmentId)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "본인 신청만 취소할 수 있습니다.");
        }
        if (statusOf(reason) != ApprovalStatus.PENDING) {
            throw new BusinessException(ErrorCode.ABSENCE_REASON_NOT_CANCELABLE);
        }

        // ★ 순서가 중요하다. 승인 취소는 조건부 UPDATE라 영속성 컨텍스트를 비우는데,
        //   그 뒤에 markDeleted를 부르면 이미 준영속이 된 엔티티라 아무 일도 일어나지 않는다
        //   — 승인만 취소되고 신청은 그대로 남는다. 취소가 실패하면 트랜잭션이 함께 되돌아간다
        reason.markDeleted();
        if (reason.getApprovalRequest() != null) {
            approvalService.cancelByRequester(reason.getApprovalRequest().getId());
        }

        log.info("사유신청 취소(앱): reasonId={}, enrollmentId={}", reasonId, enrollmentId);
    }

    /**
     * 같은 날 같은 유형 중복 방지.
     *
     * <p><b>취소·반려된 건은 세지 않는다</b> — 반려당한 학생이 사유를 고쳐 다시 내는 게
     * 정상 흐름인데, 여기서 막으면 그 날짜는 영영 다시 신청할 수 없게 된다.
     */
    private void rejectDuplicate(Long enrollmentId, LocalDate date, AbsenceReasonType type) {
        boolean exists = absenceReasonRepository
                .findByEnrollmentIdAndAttendanceDate(enrollmentId, date).stream()
                .filter(r -> !r.isDeleted())
                .filter(r -> r.getReasonType() == type)
                .anyMatch(r -> {
                    ApprovalStatus status = statusOf(r);
                    return status == ApprovalStatus.PENDING || status == ApprovalStatus.APPROVED;
                });
        if (exists) {
            throw new BusinessException(ErrorCode.ABSENCE_REASON_DUPLICATED);
        }
    }

    /**
     * 시간 범위 검증.
     *
     * <p><b>외출은 종료가 있어야 한다.</b> 없으면 언제 돌아오는지 알 수 없어
     * 복귀 태깅과 대조할 수 없다. 조퇴는 반대로 복귀가 없으므로 종료를 받으면 안 된다.
     */
    private void validatePeriod(AbsenceReasonType type, LocalTime startTime, LocalTime endTime) {
        if (type == AbsenceReasonType.OUTING) {
            if (startTime == null || endTime == null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "외출은 시작·종료 시각이 모두 필요합니다.");
            }
            if (!endTime.isAfter(startTime)) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "종료 시각이 시작보다 빠릅니다.");
            }
            return;
        }
        if (type == AbsenceReasonType.EARLY_LEAVE && startTime == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "조퇴는 시작 시각이 필요합니다.");
        }
    }

    /**
     * 관리자 목록.
     *
     * @param status 화면 탭(대기/승인/반려). {@code null}이면 전체
     * @param requestedAcademyId 조회할 지점. <b>비우면 내 지점</b>이고,
     *                           전 지점 권한자는 지정해야 한다
     */
    @Transactional(readOnly = true)
    public List<AbsenceRequestRow> list(AuthPrincipal me, Long requestedAcademyId,
                                        LocalDate from, LocalDate to,
                                        ApprovalStatus status) {
        Long academyId = me.requireAcademyScope(requestedAcademyId);

        List<AbsenceReason> reasons =
                absenceReasonRepository.findByAcademyAndPeriod(academyId, from, to);

        Map<Long, ClassAssignment> classes = classesOf(reasons);
        Instant now = Instant.now(clock);

        // ★ 담임은 맡은 학생의 신청만 본다 — 여기가 열려 있으면 학생 이름·사유가 그대로 새어나간다.
        //   반이 아니라 학생으로 거른다 — 담임 예외 지정된 학생이 새 담임에게 보여야 한다
        var studentFilter = homeroomScopeService.resolveStudentFilter(me, yearOf(from), null);
        if (studentFilter.blocksEverything()) {
            return List.of();
        }

        return reasons.stream()
                .filter(r -> status == null || statusOf(r) == status)
                .filter(r -> studentFilter.matches(r.getEnrollment().getId()))
                .map(r -> toRow(r, classes.get(r.getEnrollment().getId()), now))
                .toList();
    }

    /**
     * 화면 상단 통계 5칸.
     *
     * @param requestedAcademyId 조회할 지점. <b>비우면 내 지점</b>이고,
     *                           전 지점 권한자는 지정해야 한다
     */
    @Transactional(readOnly = true)
    public Map<String, Long> summary(AuthPrincipal me, Long requestedAcademyId,
                                     LocalDate from, LocalDate to) {
        List<AbsenceRequestRow> pending =
                list(me, requestedAcademyId, from, to, ApprovalStatus.PENDING);

        // ★ 상태별 건수는 화면 탭에 붙는 숫자다(승인 대기 2 / 승인 완료 5 / 반려 1).
        //   여기서 안 주면 화면이 전량을 받아 직접 세는데, 목록에 페이징이 들어오는
        //   순간 그 수가 "현재 페이지 안의 건수"로 바뀐다 — 20건씩 잘리면
        //   '승인 완료 5'가 실제로는 40건인 상태가 되고, 에러도 안 나고 눈에도 안 띈다.
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("pending", (long) pending.size());
        counts.put("approved",
                (long) list(me, requestedAcademyId, from, to, ApprovalStatus.APPROVED).size());
        counts.put("rejected",
                (long) list(me, requestedAcademyId, from, to, ApprovalStatus.REJECTED).size());
        // 취소 탭은 건이 있을 때만 뜬다 — 값이 없으면 그 행들이 어느 탭에도 안 잡혀 사라진다
        counts.put("canceled",
                (long) list(me, requestedAcademyId, from, to, ApprovalStatus.CANCELED).size());
        counts.put("waitingParent", pending.stream()
                .filter(r -> r.approverType() == ApproverType.PARENT).count());
        counts.put("waitingTeacher", pending.stream()
                .filter(r -> r.approverType() == ApproverType.TEACHER).count());
        counts.put("escalationCandidate", pending.stream()
                .filter(AbsenceRequestRow::escalationCandidate).count());
        // 벌점 확정 충돌은 I-10(확정 기준) 미확정이라 아직 셀 수 없다.
        // 0을 내리되 화면이 "충돌 없음"으로 오해하지 않게 별도 키로 둔다
        counts.put("penaltyConflictUnavailable", 0L);
        return counts;
    }

    private AbsenceRequestRow toRow(AbsenceReason r, ClassAssignment assignment, Instant now) {
        ApprovalRequest approval = r.getApprovalRequest();
        ApproverType approverType = approval == null ? null
                : approval.getApprovalItem().getApproverType();

        boolean escalationCandidate = approval != null
                && approval.getStatus() == ApprovalStatus.PENDING
                && approverType == ApproverType.PARENT
                && Duration.between(approval.getRequestedAt(), now)
                        .compareTo(ESCALATION_THRESHOLD) > 0;

        return new AbsenceRequestRow(
                r.getId(),
                approval == null ? null : approval.getId(),
                r.getSubmittedAt(),
                r.getEnrollment().getStudentNo(),
                r.getEnrollment().getStudent().getName(),
                assignment == null ? null : assignment.getClassMaster().getName(),
                r.getReasonType(),
                r.periodLabel(),
                r.getReasonText(),
                r.getCategory() == null ? null : r.getCategory().getName(),
                approverType,
                statusOf(r),
                // 반려 사유는 승인 요청에 남는다 — 이게 안 내려가면 화면이 "왜 반려됐는지"를
                // 표시할 값 자체가 없다. 반려가 아니면 비어 있다
                approval == null ? null : approval.getRejectReason(),
                escalationCandidate);
    }

    /**
     * 신청 상태.
     *
     * <p>승인 요청이 아직 안 붙은 건은 <b>대기</b>로 본다 — 제출은 됐고 라우팅 전이다.
     */
    private ApprovalStatus statusOf(AbsenceReason r) {
        return r.getApprovalRequest() == null
                ? ApprovalStatus.PENDING
                : r.getApprovalRequest().getStatus();
    }

    /** 반은 연도마다 새로 만들어진다 — 조회 기간의 연도를 쓴다. */
    private short yearOf(LocalDate date) {
        return (short) date.getYear();
    }

    private Map<Long, ClassAssignment> classesOf(List<AbsenceReason> reasons) {
        Map<Long, ClassAssignment> result = new HashMap<>();
        reasons.forEach(r -> classAssignmentRepository
                .findActiveFixedByEnrollmentId(r.getEnrollment().getId())
                .ifPresent(a -> result.put(r.getEnrollment().getId(), a)));
        return result;
    }

    private StudentEnrollment requireEnrollment(AuthPrincipal me, Long enrollmentId) {
        StudentEnrollment enrollment = enrollmentRepository.findById(enrollmentId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ENROLLMENT_NOT_FOUND));
        if (!me.canAccessAcademy(enrollment.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return enrollment;
    }

    /**
     * @param approvalRequestId 승인·반려는 {@code /api/v1/admin/approvals/{id}}를 그대로 부른다.
     *                          여기서 다시 구현하지 않는다
     * @param escalationCandidate 학부모가 2시간 넘게 미응답. 화면 통계가 이걸 센다
     */
    public record AbsenceRequestRow(
            Long id,
            Long approvalRequestId,
            Instant submittedAt,
            String studentNo,
            String name,
            String className,
            AbsenceReasonType type,
            String period,
            String reason,
            /** 사유 카테고리(병결 등). 안 고르고 냈으면 비어 있다 */
            String categoryName,
            ApproverType approverType,
            ApprovalStatus status,
            /** 반려 사유. 반려된 건에만 값이 있다 */
            String rejectReason,
            boolean escalationCandidate
    ) {
    }
}
