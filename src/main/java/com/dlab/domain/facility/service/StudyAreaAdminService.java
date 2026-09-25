package com.dlab.domain.facility.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.entity.AreaType;
import com.dlab.domain.facility.entity.Building;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.repository.SeatMasterRepository;
import com.dlab.domain.facility.repository.StudyAreaRepository;
import com.dlab.domain.user.entity.ClassMaster;
import com.dlab.domain.user.repository.ClassMasterRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 자습 구역(독서실) 등록·수정·삭제.
 *
 * <p>조회·배정({@link SeatLayoutService}·{@link SeatAssignmentService})만 있고 <b>구역을
 * 만드는 경로가 없어서</b> 배정 관리·좌석배치표·좌석 이탈 화면이 셋 다 막혀 있었다.
 *
 * <h2>★ {@code areaCd}는 우리만 쓰는 값이 아니다</h2>
 * 키오스크가 {@code getStudyAreaSeatInfo}·{@code getStudyAreaSeatState}를 <b>이 코드로</b>
 * 호출하고, 두 응답을 코드로 머지한다(docs/dsa-compat.md 3.7·3.8). 그래서
 * <b>생성 뒤에는 코드를 바꾸지 않는다</b> — 바꾸면 단말에서 그 구역이 통째로 사라진다.
 *
 * <h2>★ 단말이 보는 코드는 {@code kioskAreaCd}다</h2>
 * 별관은 본관과 구역명이 같을 수 있어({@link Building}) 우리 {@code areaCd}만으로는 지점
 * 안에서 유일하지 않다. 등록할 때 관을 반영한 값을 함께 저장하고 <b>키오스크에는 그 값을
 * 내린다</b> — 계약은 그대로다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StudyAreaAdminService {

    private final StudyAreaRepository studyAreaRepository;
    private final SeatMasterRepository seatMasterRepository;
    private final BuildingAdminService buildingAdminService;
    private final KioskCodeTranslator kioskCodeTranslator;
    private final ClassMasterRepository classMasterRepository;

    /** 관리 화면 목록 — 비활성 구역까지 내린다(다시 켤 수 있어야 한다). */
    public List<StudyArea> list(AuthPrincipal me, Long academyId) {
        return list(me, academyId, null, null);
    }

    /**
     * 관리 화면 목록 — 관·종류로 거른다.
     *
     * <p>둘 다 비면 전부 내린다. <b>독서실 화면과 반 좌석표 화면은 각자 자기 종류만 걸어서
     * 부른다</b> — 안 걸면 독서실 목록에 반이 섞인다.
     */
    public List<StudyArea> list(AuthPrincipal me, Long academyId, Long buildingId,
                                AreaType areaType) {
        return studyAreaRepository.findAll(
                me.requireAcademyScope(academyId), buildingId, areaType);
    }

    /**
     * 구역 등록.
     *
     * <p><b>코드는 관 안에서만 유일하다</b> — 별관에도 A·B·C 구역이 있다. 키오스크에는
     * 관까지 반영된 {@code kioskAreaCd}를 내리므로 단말 쪽 유일성은 그대로 지켜진다.
     *
     * <p><b>{@code buildingId}가 없으면 본관에 붙인다.</b> 관은 새로 생긴 개념이라 화면이
     * 아직 안 보낼 수 있는데, 그때 등록이 막히면 좌석 화면 셋이 다시 멈춘다.
     *
     * <p>지운 구역과 코드가 겹치면 <b>그 행을 되살린다.</b> 새로 만들면 좌석이 딸린 옛 구역이
     * 떠다니게 된다.
     */
    @Transactional
    public StudyArea create(AuthPrincipal me, Long academyId, Long buildingId, String areaCd,
                            String areaNm, short sortOrder) {
        return create(me, academyId, buildingId, areaCd, areaNm, sortOrder, AreaType.STUDY, null);
    }

    /**
     * 구역 등록 — 관과 종류까지.
     *
     * <p><b>교실이면 반이 필요하다.</b> 반 없는 교실 구역은 배치도가 어느 반 것인지 알 수
     * 없고, 반 하나에 좌석표는 하나다 — 둘이면 어느 쪽을 그릴지 정해지지 않는다.
     *
     * @param buildingId    비면 본관
     * @param areaType      비면 {@code STUDY}
     * @param classMasterId {@code CLASSROOM}일 때 필수, {@code STUDY}면 보내면 안 된다
     */
    @Transactional
    public StudyArea create(AuthPrincipal me, Long academyId, Long buildingId, String areaCd,
                            String areaNm, short sortOrder, AreaType areaType,
                            Long classMasterId) {
        Long resolved = me.requireAcademyScope(academyId);
        Building building = buildingId == null
                ? buildingAdminService.resolveDefault(resolved)
                : buildingAdminService.load(me, buildingId);
        if (!building.getAcademy().getId().equals(resolved)) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        AreaType type = areaType == null ? AreaType.STUDY : areaType;
        ClassMaster classMaster = resolveClassMaster(type, classMasterId, resolved);

        var existing = studyAreaRepository.findAnyByBuildingIdAndAreaCd(building.getId(), areaCd);
        if (!existing.isEmpty()) {
            StudyArea area = existing.get(0);
            if (!area.isDeleted()) {
                throw new BusinessException(ErrorCode.STUDY_AREA_DUPLICATED,
                        "%s에 이미 있는 구역 코드입니다: %s".formatted(building.getName(), areaCd));
            }
            area.reviveAs(areaNm, sortOrder, type, classMaster);
            return area;
        }

        String kioskAreaCd = kioskCodeTranslator.areaCd(building, areaCd);
        studyAreaRepository.findByAcademyIdAndKioskAreaCd(resolved, kioskAreaCd)
                .ifPresent(clash -> {
                    // 별관 코드 변환 결과가 다른 구역과 겹쳤다. 제약이 막아 주지만
                    // 그때는 화면에 이유가 안 보인다
                    throw new BusinessException(ErrorCode.STUDY_AREA_DUPLICATED,
                            "키오스크에 내려갈 구역코드가 이미 쓰이고 있습니다: " + kioskAreaCd);
                });
        return studyAreaRepository.save(new StudyArea(
                building, areaCd, kioskAreaCd, areaNm, sortOrder, type, classMaster));
    }

    /**
     * 교실이면 반을 찾아 검증한다.
     *
     * <p>독서실인데 반을 보내오면 <b>조용히 버리지 않고 막는다</b> — 화면이 종류를 잘못
     * 보냈다는 뜻이라, 통과시키면 어느 쪽 의도였는지 나중에 알 수 없다.
     */
    private ClassMaster resolveClassMaster(AreaType type, Long classMasterId, Long academyId) {
        if (type != AreaType.CLASSROOM) {
            if (classMasterId != null) {
                throw new BusinessException(ErrorCode.INVALID_REQUEST,
                        "독서실 구역에는 반을 지정할 수 없습니다.");
            }
            return null;
        }
        if (classMasterId == null) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "반 교실 구역에는 반이 필요합니다.");
        }
        ClassMaster classMaster = classMasterRepository.findById(classMasterId)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.CLASS_NOT_FOUND));
        // 다른 지점 반을 붙이면 그 지점 배치도에 남의 반이 뜬다.
        if (!classMaster.getAcademy().getId().equals(academyId)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "다른 지점의 반은 지정할 수 없습니다.");
        }
        studyAreaRepository.findByClassMasterId(classMasterId).ifPresent(a -> {
            throw new BusinessException(ErrorCode.STUDY_AREA_DUPLICATED,
                    "이미 좌석표가 있는 반입니다: " + classMaster.getName());
        });
        return classMaster;
    }

    /** 이름·정렬·노출 수정. {@code areaCd}는 대상이 아니다(키오스크 계약). */
    @Transactional
    public StudyArea update(AuthPrincipal me, Long studyAreaId, String areaNm, Short sortOrder,
                            Boolean active) {
        StudyArea area = load(me, studyAreaId);
        area.update(areaNm, sortOrder);
        if (active != null) {
            area.changeActive(active);
        }
        return area;
    }

    /**
     * 구역 삭제(soft).
     *
     * <p><b>좌석이 남아 있으면 거부한다.</b> 구역만 지우면 좌석은 그대로 남아
     * 배치도에서는 사라지는데 키오스크 조회에는 계속 뜨는, 원인을 찾기 어려운 상태가 된다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long studyAreaId) {
        StudyArea area = load(me, studyAreaId);
        long seats = seatMasterRepository.countByStudyAreaId(studyAreaId);
        if (seats > 0) {
            throw new BusinessException(ErrorCode.STUDY_AREA_HAS_SEATS,
                    "좌석 " + seats + "개가 남아 있습니다. 좌석을 먼저 삭제하세요.");
        }
        area.markDeleted();
    }

    /** 구역을 찾고 지점 권한까지 검사한다. */
    public StudyArea load(AuthPrincipal me, Long studyAreaId) {
        StudyArea area = studyAreaRepository.findById(studyAreaId)
                .filter(a -> !a.isDeleted())
                .orElseThrow(() -> new BusinessException(ErrorCode.STUDY_AREA_NOT_FOUND));
        if (!me.canAccessAcademy(area.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return area;
    }

    /** 구역에 속한 좌석 수(삭제분 제외). */
    public long seatCount(Long studyAreaId) {
        return seatMasterRepository.countByStudyAreaId(studyAreaId);
    }
}
