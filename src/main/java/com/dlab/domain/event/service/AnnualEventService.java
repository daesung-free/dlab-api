package com.dlab.domain.event.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.event.entity.AnnualEvent;
import com.dlab.domain.event.entity.AnnualEventType;
import com.dlab.domain.event.repository.AnnualEventRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 연간 행사 (F-4.11-10).
 *
 * <h2>전 지점 공통은 본사만 등록한다</h2>
 * 지점 관리자가 공통 행사를 넣으면 <b>다른 지점 달력까지 바뀐다.</b> 공휴일에서 같은
 * 판단을 했다(§7).
 */
@Service
@RequiredArgsConstructor
public class AnnualEventService {

    private final AnnualEventRepository eventRepository;

    /** 그 해 행사 — 지점 행사 + 전 지점 공통. */
    @Transactional(readOnly = true)
    public List<AnnualEvent> findAllOfYear(AuthPrincipal me, Long academyId, short year) {
        return eventRepository.findAllOfYear(year, me.requireAcademyScope(academyId));
    }

    /**
     * 학습계획·달력에 얹을 행사.
     *
     * <p>★ <b>계획 행으로 복사하지 않는다.</b> 조회 시 합쳐 내리므로 행사를 고치면
     * 그 즉시 반영된다 — 복사하면 수정·삭제 때 흩어진 행을 따라다녀야 한다.
     */
    @Transactional(readOnly = true)
    public List<AnnualEvent> findForPlan(AuthPrincipal me, Long academyId,
                                         LocalDate from, LocalDate to) {
        Long scope = me.requireAcademyScope(academyId);
        return eventRepository.findInPeriod((short) from.getYear(), scope, from, to);
    }

    @Transactional
    public AnnualEvent create(AuthPrincipal me, Long academyId, short year, String name,
                              LocalDate startDate, LocalDate endDate, AnnualEventType type,
                              boolean showInPlan, String memo) {
        requirePeriod(startDate, endDate);
        Long scope = resolveScope(me, academyId);
        return eventRepository.save(new AnnualEvent(year, scope, name,
                startDate, endDate, type, showInPlan, memo));
    }

    /**
     * 전년도 행사 복사 — 날짜를 연도 차이만큼 민다(2/29 → 2/28).
     *
     * <p>기초 데이터 전년도 복사({@code yearly-copy})는 새 해에 데이터가 하나라도 있으면 통째로
     * 거부해서, 행사만 옮길 방법이 없었다. 여기는 <b>행사만</b> 옮기고 <b>같은 이름·같은 시작일은
     * 건너뛴다</b> — 두 번 눌러도 두 벌이 되지 않는다.
     *
     * <p>{@code academyId}를 비우면 전 지점 공통 행사(본사만). 지점을 넣으면 그 지점 행사만 옮긴다 —
     * 공통 행사를 지점 행사로 늘리지 않는다. 시험·설명회는 해마다 날이 달라 <b>복사 후 확인</b>이 필요하다.
     */
    @Transactional
    public CopyResult copyYear(AuthPrincipal me, Long academyId, short fromYear, short toYear) {
        if (toYear <= fromYear) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "새 연도는 원본 연도보다 커야 합니다.");
        }
        Long scope = resolveScope(me, academyId);
        int delta = toYear - fromYear;
        java.util.Set<String> existing = eventRepository.findAllOfYear(toYear, scope).stream()
                .filter(e -> java.util.Objects.equals(e.getAcademyId(), scope))
                .map(e -> e.getName() + "|" + e.getStartDate())
                .collect(java.util.stream.Collectors.toSet());

        int copied = 0;
        int skipped = 0;
        for (AnnualEvent src : eventRepository.findAllOfYear(fromYear, scope)) {
            if (!java.util.Objects.equals(src.getAcademyId(), scope)) {
                continue;
            }
            LocalDate start = src.getStartDate().plusYears(delta);
            if (!existing.add(src.getName() + "|" + start)) {
                skipped++;
                continue;
            }
            eventRepository.save(new AnnualEvent(toYear, scope, src.getName(), start,
                    src.getEndDate() == null ? null : src.getEndDate().plusYears(delta),
                    src.getEventType(), src.isShowInPlan(), src.getMemo()));
            copied++;
        }
        return new CopyResult(copied, skipped);
    }

    /** @param skipped 새 해에 같은 이름·시작일이 이미 있어 건너뛴 수 */
    @io.swagger.v3.oas.annotations.media.Schema(name = "AnnualEventCopyResult")
    public record CopyResult(int copied, int skipped) {
    }

    @Transactional
    public AnnualEvent update(AuthPrincipal me, Long eventId, String name,
                              LocalDate startDate, LocalDate endDate, AnnualEventType type,
                              Boolean showInPlan, String memo) {
        AnnualEvent event = require(me, eventId);
        LocalDate start = startDate == null ? event.getStartDate() : startDate;
        LocalDate end = endDate == null ? event.getEndDate() : endDate;
        requirePeriod(start, end);
        event.change(name, startDate, endDate, type, showInPlan, memo);
        return event;
    }

    /**
     * 삭제.
     *
     * <p><b>soft delete 다.</b> 지난 행사가 어느 날에 있었는지가 학습계획·통계의 근거로
     * 남아야 한다 — 공휴일과 같은 판단이다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long eventId) {
        require(me, eventId).markDeleted();
    }

    private AnnualEvent require(AuthPrincipal me, Long eventId) {
        AnnualEvent event = eventRepository.findById(eventId)
                .filter(e -> !e.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND,
                        "행사를 찾을 수 없습니다."));
        if (event.isShared()) {
            // 공통 행사는 전 지점 달력에 뜬다 — 지점이 고치면 남의 지점이 같이 바뀐다
            requireAllAcademy(me);
        } else {
            me.requireAcademyScope(event.getAcademyId());
        }
        return event;
    }

    /** 지점을 지정하지 않으면 전 지점 공통이고, 그건 본사만 만들 수 있다. */
    private Long resolveScope(AuthPrincipal me, Long academyId) {
        if (academyId == null) {
            requireAllAcademy(me);
            return null;
        }
        return me.requireAcademyScope(academyId);
    }

    private void requireAllAcademy(AuthPrincipal me) {
        if (!me.allAcademy()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "전 지점 공통 행사는 본사에서만 등록·수정할 수 있습니다.");
        }
    }

    private void requirePeriod(LocalDate start, LocalDate end) {
        if (end.isBefore(start)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "종료일이 시작일보다 앞설 수 없습니다.");
        }
    }
}
