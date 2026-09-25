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
     * <h2>{@code seatCdOffset} 을 생략하면 서버가 정한다</h2>
     * {@code annex} 가 참이고 값이 없으면 <b>기존 최대 offset + 1000</b> 으로 채번한다.
     * 화면이 계산해서 보내면 <b>두 사람이 동시에 등록할 때 같은 값이 나온다</b> — 학번
     * 채번을 서버가 하는 것과 같은 이유다.
     *
     * <h2>★ 겹치면 여기서 막는다</h2>
     * 같은 offset 을 가진 별관이 둘이면 <b>나중 관은 좌석을 한 자리도 못 만든다</b>
     * (변환 결과가 통째로 겹친다). 좌석 등록 단계에서도 걸리지만, 그때는 이미 관·구역을
     * 만들어 둔 뒤라 어디서부터 잘못됐는지 되짚어야 한다.
     *
     * <p><b>다만 이 검사가 충돌을 다 막지는 못한다.</b> offset 이 달라도 본관에 1001번이
     * 실재하면 2관 1번과 겹친다 — 그건 좌석 등록의
     * {@code rejectIfKioskCodeTaken} 이 잡는다. 여기는 <b>미리 알려주는</b> 자리다.
     *
     * <h2>본관은 지점당 하나다</h2>
     * 둘이면 "본관"이라는 말이 의미를 잃고, {@code buildingId} 없이 들어온 구역 등록이
     * 어디에 붙일지 정할 수 없어 통째로 막힌다({@link #resolveDefault}).
     */
    @Transactional
    public Building create(AuthPrincipal me, Long academyId, String code, String name,
                           short sortOrder, Integer seatCdOffset, boolean annex) {
        Long resolved = me.requireAcademyScope(academyId);
        Academy academy = academyRepository.findById(resolved)
                .orElseThrow(() -> new BusinessException(ErrorCode.ACADEMY_NOT_FOUND));

        List<Building> existing = buildingRepository.findAllByAcademyId(resolved);
        int offset = resolveOffset(existing, seatCdOffset, annex);

        if (offset == 0 && existing.stream().anyMatch(Building::isMain)) {
            throw new BusinessException(ErrorCode.BUILDING_DUPLICATED,
                    "본관은 지점마다 하나입니다. 별관으로 등록하세요.");
        }
        if (offset != 0) {
            existing.stream()
                    .filter(b -> b.getSeatCdOffset() == offset)
                    .findFirst()
                    .ifPresent(clash -> {
                        throw new BusinessException(ErrorCode.BUILDING_DUPLICATED,
                                "%s이(가) 이미 %d 번호대를 쓰고 있습니다. 겹치면 나중에 만든 관은 좌석을 "
                                        .formatted(clash.getName(), offset)
                                        + "한 자리도 만들 수 없습니다.");
                    });
        }
        if (existing.stream().anyMatch(b -> b.getCode().equals(code))) {
            throw new BusinessException(ErrorCode.BUILDING_DUPLICATED,
                    "이미 있는 관 코드입니다: " + code);
        }
        return buildingRepository.save(
                new Building(academy, code, name, sortOrder, offset));
    }

    /**
     * 쓸 offset 을 정한다.
     *
     * <p>값이 오면 그대로 쓰되 범위만 본다. 없으면 별관일 때만 채번한다 —
     * 본관은 0 이 유일한 값이라 정할 것이 없다.
     */
    private int resolveOffset(List<Building> existing, Integer requested, boolean annex) {
        if (requested != null) {
            if (requested != 0 && requested < MIN_ANNEX_OFFSET) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "별관 좌석번호 오프셋은 " + MIN_ANNEX_OFFSET + " 이상이어야 합니다. "
                                + "작은 값은 본관 좌석번호와 겹칩니다.");
            }
            return requested;
        }
        if (!annex) {
            return 0;
        }
        int max = existing.stream().mapToInt(Building::getSeatCdOffset).max().orElse(0);
        return Math.max(max + MIN_ANNEX_OFFSET, MIN_ANNEX_OFFSET);
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
