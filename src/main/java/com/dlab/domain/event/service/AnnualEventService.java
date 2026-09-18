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
