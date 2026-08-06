package com.dlab.domain.period.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.period.entity.DayType;
import com.dlab.domain.period.entity.PeriodMaster;
import com.dlab.domain.period.entity.PeriodType;
import com.dlab.domain.period.repository.PeriodMasterRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 교시·시간 편집 (F-4.10-1).
 *
 * <p><b>별도 화면은 없다</b>(0803 폐기). 편집은 학습계획 화면 안에서 이뤄지고 여기는 API만 낸다.
 *
 * <h2>이 마스터 하나를 여러 곳이 본다</h2>
 * 출결 판정(키오스크 태깅)·순공시간 산출·학습계획 그리드가 <b>같은 교시 마스터</b>를 쓴다.
 * 두 벌로 만들면 교시를 바꿨을 때 한쪽만 반영돼 "시간표엔 있는데 태깅은 거부되는" 상태가 된다.
 * 그래서 편집이 조심스럽다 — 아래 두 규칙이 그 때문에 있다.
 *
 * <h2>겹치면 안 된다</h2>
 * 한 시각이 두 교시에 걸리면 {@code covers()}가 둘을 반환해 출결 판정과 순공시간 계산이
 * 흔들린다. 경계가 맞닿는 것(이전 종료 == 다음 시작)은 정상이다.
 *
 * <h2>전부 지울 수 없다</h2>
 * 교시가 하나도 없는 날은 <b>"운영일 아님"</b>으로 해석된다 — 출결 확정 배치가 통째로
 * 건너뛰고 키오스크는 {@code code 113}(시간표 없음)을 뱉어 그날 태깅이 전원 거부된다.
 * 실수로 마지막 하나를 지우는 걸 막는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeriodService {

    private final PeriodMasterRepository periodRepository;
    private final AcademyRepository academyRepository;

    /** 연도 전체(요일 구분·시작시각 순). 편집 화면이 평일·토요일을 함께 편다. */
    @Transactional(readOnly = true)
    public List<PeriodMaster> findAll(AuthPrincipal me, Long academyId, short year) {
        return periodRepository.findByYear(requireAcademyAccess(me, academyId), year);
    }

    /** 특정 요일 구분만. */
    @Transactional(readOnly = true)
    public List<PeriodMaster> findByDayType(AuthPrincipal me, Long academyId,
                                            short year, DayType dayType) {
        return periodRepository.findByDayType(
                requireAcademyAccess(me, academyId), year, dayType);
    }

    @Transactional
    public PeriodMaster create(AuthPrincipal me, Long academyId, short year, DayType dayType,
                               short periodNo, String name, PeriodType periodType,
                               LocalTime startTime, LocalTime endTime, boolean planable) {

        Long resolvedAcademyId = requireAcademyAccess(me, academyId);
        validateTimeRange(startTime, endTime);

        List<PeriodMaster> siblings =
                periodRepository.findByDayType(resolvedAcademyId, year, dayType);
        requireNoDuplicatedNo(siblings, periodNo, null);
        requireNoOverlap(siblings, startTime, endTime, null);

        Academy academy = academyRepository.findById(resolvedAcademyId)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        PeriodMaster saved = periodRepository.save(new PeriodMaster(
                academy, year, periodNo, name, dayType, periodType,
                startTime, endTime, planable));

        log.info("교시 등록: 지점={}, 연도={}, 요일={}, {}교시 {}~{}",
                resolvedAcademyId, year, dayType, periodNo, startTime, endTime);
        return saved;
    }

    @Transactional
    public PeriodMaster update(AuthPrincipal me, Long id, short periodNo, String name,
                               PeriodType periodType, LocalTime startTime, LocalTime endTime,
                               boolean planable) {

        PeriodMaster period = requirePeriod(me, id);
        validateTimeRange(startTime, endTime);

        List<PeriodMaster> siblings = periodRepository.findByDayType(
                period.getAcademy().getId(), period.getYear(), period.getDayType());
        requireNoDuplicatedNo(siblings, periodNo, id);
        requireNoOverlap(siblings, startTime, endTime, id);

        period.update(periodNo, name, periodType, startTime, endTime, planable);

        log.info("교시 수정: id={}, {}교시 {}~{}", id, periodNo, startTime, endTime);
        return period;
    }

    /**
     * 삭제(soft).
     *
     * <p><b>물리 삭제하지 않는다</b> — 지난 출결이 어느 교시 구성으로 판정됐는지
     * 추적이 끊긴다. 학습계획도 교시를 참조한다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long id) {
        PeriodMaster period = requirePeriod(me, id);

        long remaining = periodRepository.findByDayType(
                        period.getAcademy().getId(), period.getYear(), period.getDayType())
                .size();
        if (remaining <= 1) {
            throw new BusinessException(ErrorCode.PERIOD_LAST_ONE);
        }

        period.markDeleted();
        log.info("교시 삭제: id={}, 지점={}, 요일={}",
                id, period.getAcademy().getId(), period.getDayType());
    }

    private void validateTimeRange(LocalTime startTime, LocalTime endTime) {
        if (!startTime.isBefore(endTime)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "종료 시각이 시작보다 빠르거나 같습니다.");
        }
    }

    /** {@code excludeId}는 수정 중인 자기 자신 — 자기와 비교하면 항상 걸린다. */
    private void requireNoDuplicatedNo(List<PeriodMaster> siblings, short periodNo,
                                       Long excludeId) {
        boolean duplicated = siblings.stream()
                .filter(p -> !p.getId().equals(excludeId))
                .anyMatch(p -> p.getPeriodNo() == periodNo);
        if (duplicated) {
            throw new BusinessException(ErrorCode.PERIOD_NO_DUPLICATED);
        }
    }

    private void requireNoOverlap(List<PeriodMaster> siblings, LocalTime startTime,
                                  LocalTime endTime, Long excludeId) {
        siblings.stream()
                .filter(p -> !p.getId().equals(excludeId))
                .filter(p -> p.overlaps(startTime, endTime))
                .findFirst()
                .ifPresent(p -> {
                    throw new BusinessException(ErrorCode.PERIOD_TIME_OVERLAPPED,
                            "%d교시(%s~%s)와 겹칩니다."
                                    .formatted(p.getPeriodNo(), p.getStartTime(), p.getEndTime()));
                });
    }

    private PeriodMaster requirePeriod(AuthPrincipal me, Long id) {
        PeriodMaster period = periodRepository.findById(id)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.PERIOD_NOT_FOUND));
        if (!me.canAccessAcademy(period.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return period;
    }

    /**
     * 지점 확인.
     *
     * <p>전 지점 권한자는 대상 지점을 지정해야 한다 — 교시는 지점 공통이라
     * "전 지점 일괄"이 성립하지 않는다(지점마다 점심시간이 다르다).
     */
    private Long requireAcademyAccess(AuthPrincipal me, Long academyId) {
        Long resolved = academyId != null ? academyId : me.academyScopeFilter();
        if (resolved == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "지점을 지정해야 합니다.");
        }
        if (!me.canAccessAcademy(resolved)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return resolved;
    }
}
