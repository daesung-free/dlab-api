package com.dlab.domain.approval.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.approval.entity.ApprovalItem;
import com.dlab.domain.approval.entity.ApproverType;
import com.dlab.domain.approval.entity.RequestType;
import com.dlab.domain.approval.repository.ApprovalItemRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 승인 라우팅 정책 관리 (F-4.11-5).
 *
 * <p>지점 × 연도 × 신청유형마다 한 행이고, {@code ApprovalService.create()}가 신청할 때마다
 * 이 행을 찾는다. <b>행이 없으면 그 유형의 신청 자체가 거절된다</b> — 지금까지 이 행을
 * 만드는 경로가 테스트 코드뿐이었다.
 *
 * <h2>★ 승인 주체는 아직 확정되지 않았다 (I-12, 최우선·미해결)</h2>
 * 확정된 건 <b>방화벽(학부모 1차 → 10분 후 담당선생님)</b>뿐이다. 그래서 값을 코드에 박지
 * 않고 데이터로 둔다 — 확정되면 화면에서 행만 채우면 되고 배포가 필요 없다.
 * {@code penalty_rule}을 데이터로 둔 것과 같은 이유다.
 *
 * <h2>전년도 복사는 여기 없다</h2>
 * {@code YearlySnapshotService}가 이미 {@code approval_item}을 복사한다. 여기서 또 만들면
 * 복사 경로가 둘이 되어 {@code copied_from_id}가 어긋난다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ApprovalItemService {

    private final ApprovalItemRepository approvalItemRepository;
    private final AcademyRepository academyRepository;

    /**
     * 그 지점·연도의 정책 전체.
     *
     * <p><b>설정이 없는 유형도 빈 줄로 함께 내린다</b> — 화면이 3종을 다 그려야
     * "이 유형은 아직 안 정했다"가 보인다. 목록에서 빠지면 없는 줄도 모른다.
     */
    public List<Row> list(AuthPrincipal me, Long academyId, short year) {
        Long scope = resolveScope(me, academyId);
        List<ApprovalItem> items =
                approvalItemRepository.findByAcademyIdAndYearAndDeletedFalse(scope, year);

        return java.util.Arrays.stream(RequestType.values())
                .map(type -> items.stream()
                        .filter(i -> i.getRequestType() == type)
                        .findFirst()
                        .map(Row::of)
                        .orElseGet(() -> Row.empty(type)))
                .toList();
    }

    /**
     * 정책 저장. 유형당 1행이라 있으면 고치고 없으면 만든다.
     *
     * <p><b>이미 대기중인 신청에는 소급되지 않는다</b> — 승인 요청이 생성 시점에 타임아웃을
     * 복사해 갖기 때문이다.
     */
    @Transactional
    public ApprovalItem save(AuthPrincipal me, Long academyId, short year,
                             RequestType requestType, ApproverType approverType,
                             Short timeoutMinutes, ApproverType escalationApproverType) {

        Long scope = resolveScope(me, academyId);
        Policy policy = verify(approverType, timeoutMinutes, escalationApproverType);

        return approvalItemRepository
                .findByAcademyIdAndYearAndRequestTypeAndDeletedFalse(scope, year, requestType)
                .map(existing -> {
                    existing.changePolicy(policy.approverType(), policy.timeoutMinutes(),
                            policy.escalationApproverType());
                    return existing;
                })
                .orElseGet(() -> approvalItemRepository.save(new ApprovalItem(
                        requireAcademy(scope), year, requestType, policy.approverType(),
                        policy.timeoutMinutes(), policy.escalationApproverType())));
    }

    /**
     * 정책 삭제.
     *
     * <p><b>삭제하면 그 유형의 신청이 전부 막힌다.</b> 화면에서 경고할 것 —
     * "설정 안 함"과 "삭제"가 결과적으로 같다는 걸 관리자가 알아야 한다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long academyId, short year, RequestType requestType) {
        Long scope = resolveScope(me, academyId);
        approvalItemRepository.findByAcademyIdAndYearAndRequestTypeAndDeletedFalse(scope, year, requestType)
                .orElseThrow(() -> new BusinessException(ErrorCode.APPROVAL_ITEM_NOT_FOUND))
                .markDeleted();
    }

    // ─────────────────────────────────────────────────────────

    /**
     * 정책 정합성 검사.
     *
     * <p>세 가지를 막는다:
     * <ul>
     *   <li><b>{@code AUTO}에 타임아웃·에스컬레이션</b> — 자동 승인에 무응답이 있을 수 없다.
     *       거절하지 않고 지운다(화면이 값을 남긴 채 유형만 바꾸는 흐름이 흔하다)</li>
     *   <li><b>한쪽만 채운 에스컬레이션</b> — {@code hasEscalation()}이 둘 다 있어야 true라
     *       한쪽만 채우면 <b>저장은 되는데 에스컬레이션이 조용히 안 돈다</b></li>
     *   <li><b>{@code PARENT}로 에스컬레이션</b> — 학부모는 최대 1인(I-12 0803)이라
     *       넘길 다른 학부모가 없다</li>
     * </ul>
     */
    private Policy verify(ApproverType approverType, Short timeoutMinutes,
                          ApproverType escalationApproverType) {
        if (approverType == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "승인 주체를 선택해 주세요.");
        }
        if (approverType == ApproverType.AUTO) {
            return new Policy(ApproverType.AUTO, null, null);
        }
        if ((timeoutMinutes == null) != (escalationApproverType == null)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "타임아웃과 에스컬레이션 대상은 함께 지정해야 합니다.");
        }
        if (timeoutMinutes != null && timeoutMinutes <= 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "타임아웃은 1분 이상이어야 합니다.");
        }
        if (escalationApproverType != null && escalationApproverType != ApproverType.TEACHER) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "에스컬레이션 대상은 담당선생님만 지정할 수 있습니다.");
        }
        return new Policy(approverType, timeoutMinutes, escalationApproverType);
    }

    private Academy requireAcademy(Long academyId) {
        return academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
    }

    /** 지점 스코프는 서버가 강제한다 — 요청 값을 그대로 믿으면 남의 지점 정책을 바꿀 수 있다. */
    private Long resolveScope(AuthPrincipal me, Long requested) {
        return me.requireAcademyScope(requested);
    }

    private record Policy(ApproverType approverType, Short timeoutMinutes,
                          ApproverType escalationApproverType) {
    }

    /**
     * 정책 한 줄.
     *
     * @param configured {@code false}면 아직 안 정한 유형이다 — <b>그 신청은 거절된다</b>
     * @param copiedFrom 전년도 복사 원본. {@code null}이면 그 해에 새로 만든 것이다
     */
    public record Row(RequestType requestType, boolean configured, ApproverType approverType,
                      Short timeoutMinutes, ApproverType escalationApproverType,
                      Long itemId, Long copiedFrom) {

        static Row of(ApprovalItem item) {
            return new Row(item.getRequestType(), true, item.getApproverType(),
                    item.getTimeoutMinutes(), item.getEscalationApproverType(),
                    item.getId(), item.getCopiedFromId());
        }

        static Row empty(RequestType requestType) {
            return new Row(requestType, false, null, null, null, null, null);
        }
    }
}
