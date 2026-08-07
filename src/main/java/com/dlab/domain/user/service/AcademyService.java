package com.dlab.domain.user.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.common.security.Role;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 지점 관리.
 *
 * <h2>★ 지점을 새로 만드는 기능은 없다</h2>
 * 지점은 9개로 고정이고 {@code acad_cd}는 대성전산이 부여한 값이라 우리가 만들 일이 없다.
 * 요구사항정의서에도 등록 화면이 없다 — 최초 심기는 마이그레이션이 한다.
 *
 * <h2>목록이 필요한 이유</h2>
 * 교시·공지·좌석·공휴일 화면이 모두 "지점 고르기"를 요구하는데, 프론트가 그 목록을
 * 얻을 곳이 없었다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AcademyService {

    private final AcademyRepository academyRepository;

    /**
     * 지점 목록.
     *
     * <p><b>지점 관리자에게는 자기 지점만 보인다.</b> 다른 지점 이름·코드까지 내릴 이유가 없고,
     * 화면 셀렉트에 남의 지점이 뜨면 고를 수 있는 것처럼 보인다.
     *
     * @param includeInactive 비활성 지점 포함 여부. 기본은 활성만
     */
    @Transactional(readOnly = true)
    public List<Academy> findAll(AuthPrincipal me, boolean includeInactive) {
        return academyRepository.findAll().stream()
                .filter(a -> !a.isDeleted())
                .filter(a -> includeInactive || a.isActive())
                .filter(a -> me.canAccessAcademy(a.getId()))
                .sorted(Comparator.comparing(Academy::getAcadCd))
                .toList();
    }

    @Transactional(readOnly = true)
    public Academy findOne(AuthPrincipal me, Long academyId) {
        return requireAccessible(me, academyId);
    }

    /**
     * 기본정보 수정.
     *
     * <p>지점 관리자도 자기 지점은 고칠 수 있다 — 등원 기준 시각은 지점 운영 값이라
     * 본사만 만질 수 있게 하면 매번 요청해야 한다.
     */
    @Transactional
    public Academy update(AuthPrincipal me, Long academyId, String acadNm, String fullNm,
                          LocalTime attendanceDeadline) {

        Academy academy = requireAccessible(me, academyId);
        if (!me.hasRole(Role.SUPER_ADMIN) && !me.hasRole(Role.BRANCH_ADMIN)) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "지점 정보는 지점관리자 이상만 수정할 수 있습니다.");
        }

        LocalTime before = academy.getAttendanceDeadline();
        academy.updateInfo(acadNm, fullNm, attendanceDeadline);

        if (!before.equals(attendanceDeadline)) {
            // 지각 판정 기준이 바뀌었다. 이 시점부터 판정이 달라진다
            log.info("등원 기준 시각 변경: 지점={}, {} → {}, 처리자={}",
                    academy.getAcadCd(), before, attendanceDeadline, me.accountId());
        }
        return academy;
    }

    /**
     * 활성·비활성.
     *
     * <p><b>본사만 할 수 있다.</b> 끄면 그 지점 전체가 멈춘다 — 키오스크 토큰 발급부터
     * 막혀 태깅이 전면 중단된다. 지점 관리자가 자기 지점을 끄는 사고를 막는다.
     */
    @Transactional
    public Academy changeActive(AuthPrincipal me, Long academyId, boolean active) {
        if (!me.allAcademy()) {
            throw new BusinessException(ErrorCode.FORBIDDEN,
                    "지점 활성 여부는 본사만 변경할 수 있습니다.");
        }

        Academy academy = academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        academy.changeActive(active);

        log.warn("지점 활성 상태 변경: 지점={}, active={}, 처리자={}",
                academy.getAcadCd(), active, me.accountId());
        return academy;
    }

    private Academy requireAccessible(AuthPrincipal me, Long academyId) {
        Academy academy = academyRepository.findById(academyId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));
        if (!me.canAccessAcademy(academy.getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return academy;
    }
}
