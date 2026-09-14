package com.dlab.domain.facility.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.entity.Building;
import com.dlab.domain.facility.repository.BuildingRepository;
import com.dlab.domain.user.entity.Academy;
import com.dlab.domain.user.repository.AcademyRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 관(본관/별관) 등록·수정.
 *
 * <h2>★ offset 은 등록할 때 한 번만 정한다</h2>
 * 좌석을 만들 때 이 값이 {@code kiosk_seat_cd}에 이미 반영돼 저장되기 때문에, 나중에
 * 바꾸면 <b>그 관의 기존 좌석들과 어긋난다.</b> 그래서 수정 대상에서 뺐다 — 구역 코드·좌석
 * 코드를 수정 대상에서 뺀 것과 같은 이유다.
 *
 * <h2>★ 삭제를 만들지 않았다</h2>
 * 관을 지우면 그 아래 구역·좌석·배정 이력이 통째로 떠다니게 된다. 쓰지 않는 관은
 * {@code active} 로 내린다. 잘못 만든 관은 구역이 하나도 없을 때만 지울 수 있게 했다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BuildingAdminService {

    /** 별관 번호대. 1000 미만이면 본관 좌석과 겹칠 여지가 크다. */
    private static final int MIN_ANNEX_OFFSET = 1000;

    private final BuildingRepository buildingRepository;
    private final AcademyRepository academyRepository;

    public List<Building> list(AuthPrincipal me, Long academyId) {
        return buildingRepository.findAllByAcademyId(me.requireAcademyScope(academyId));
    }

    /**
     * 관 등록.
     *
     * <p>{@code seatCdOffset} 이 0이면 본관, 그 이상이면 별관이다. 별관은 <b>1000 이상</b>만
     * 받는다 — DSA 가 1000번대를 쓰던 관례와 맞추고, 100 같은 작은 값이면 본관 101번과
     * 별관 1번의 변환 결과가 곧바로 겹친다.
     */
    @Transactional
    public Building create(AuthPrincipal me, Long academyId, String code, String name,
                           short sortOrder, int seatCdOffset) {
        Long resolved = me.requireAcademyScope(academyId);
        Academy academy = academyRepository.findById(resolved)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        if (seatCdOffset != 0 && seatCdOffset < MIN_ANNEX_OFFSET) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "별관 좌석번호 오프셋은 " + MIN_ANNEX_OFFSET + " 이상이어야 합니다. "
                            + "작은 값은 본관 좌석번호와 겹칩니다.");
        }
        if (buildingRepository.findByAcademyIdAndCode(resolved, code).isPresent()) {
            throw new BusinessException(ErrorCode.BUILDING_DUPLICATED,
                    "이미 있는 관 코드입니다: " + code);
        }
        return buildingRepository.save(
                new Building(academy, code, name, sortOrder, seatCdOffset));
    }

    /** 이름·정렬만. 코드·offset 은 대상이 아니다(저장된 키오스크 코드와 어긋난다). */
    @Transactional
    public Building update(AuthPrincipal me, Long buildingId, String name, Short sortOrder,
                           Boolean active) {
        Building building = load(me, buildingId);
        building.update(name, sortOrder);
        if (active != null) {
            building.changeActive(active);
        }
        return building;
    }

    /**
     * 관 삭제(soft) — <b>구역이 하나도 없을 때만</b>.
     *
     * <p>구역이 남은 채 지우면 그 구역들이 없는 관을 가리키고, 키오스크에는 계속 뜬다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long buildingId) {
        Building building = load(me, buildingId);
        long areas = buildingRepository.countAreas(buildingId);
        if (areas > 0) {
            throw new BusinessException(ErrorCode.BUILDING_HAS_AREAS,
                    "구역 " + areas + "개가 남아 있습니다. 구역을 먼저 삭제하세요.");
        }
        building.markDeleted();
    }

    /** 관을 찾고 지점 권한까지 검사한다. */
    public Building load(AuthPrincipal me, Long buildingId) {
        Building building = buildingRepository.findById(buildingId)
                .filter(b -> !b.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.BUILDING_NOT_FOUND));
        if (!me.canAccessAcademy(building.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return building;
    }

    /**
     * 구역 등록에서 관을 못 받았을 때 붙일 곳.
     *
     * <p><b>화면이 아직 관을 안 보내도 기존처럼 동작해야 한다.</b> 관 개념이 새로 생긴
     * 것이라 프런트가 따라오기 전까지 구역 등록이 막히면 좌석 화면 셋이 다시 멈춘다.
     * 마이그레이션이 지점마다 본관을 하나씩 만들어 뒀으므로 보통은 그게 잡힌다.
     *
     * <p>본관이 여럿이면 <b>정하지 않고 거부한다</b> — 아무거나 고르면 구역이 엉뚱한 관에
     * 붙고, 그 사실은 한참 뒤 좌석번호가 겹칠 때야 드러난다.
     */
    public Building resolveDefault(Long academyId) {
        List<Building> candidates = buildingRepository.findMainCandidates(academyId);
        if (candidates.isEmpty()) {
            throw new BusinessException(ErrorCode.BUILDING_NOT_FOUND,
                    "이 지점에 관이 없습니다. 관을 먼저 등록하세요.");
        }
        if (candidates.size() > 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "본관이 여러 개입니다. 어느 관에 만들지 지정하세요.");
        }
        return candidates.get(0);
    }
}
