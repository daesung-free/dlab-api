package com.dlab.domain.facility.service;

import com.dlab.common.exception.BusinessException;
import com.dlab.common.exception.ErrorCode;
import com.dlab.common.security.AuthPrincipal;
import com.dlab.domain.facility.entity.SeatMaster;
import com.dlab.domain.facility.entity.StudyArea;
import com.dlab.domain.facility.repository.SeatAssignmentRepository;
import com.dlab.domain.facility.repository.SeatMasterRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 좌석 등록·수정·삭제.
 *
 * <h2>★ 격자 등록이 기본 경로다</h2>
 * 한 구역이 수십 석이라 한 칸씩 등록하게 하면 <b>실무에서 안 쓴다</b> — 좌표를 사람이
 * 하나씩 채워 넣어야 하고, 한 번 틀리면 배치도가 어긋난 채로 남는다. 행·열과 시작 번호만
 * 받아 좌표·코드를 서버가 만든다. 통로처럼 비는 칸은 {@code skips}로 빼고, <b>번호는 빈
 * 칸을 건너뛰고 이어진다</b>(실제 좌석표가 그렇게 붙어 있다).
 *
 * <h2>★ 같은 지점에 같은 {@code seatCd}를 두 번 넣을 수 없다</h2>
 * {@code seat_master}에 {@code UNIQUE (academy_id, seat_cd)}가 걸려 있다. <b>별관(동탄2관)
 * 처럼 본관과 좌석번호가 겹치는 운영이 실제로 있는데</b>, 「관」축이 아직 스키마에 없어서
 * 지금은 코드로 구분할 수밖에 없다(DSA가 별관을 1000번대로 돌려 쓰던 이유가 이것이다).
 * 그래서 <b>충돌을 DB 제약에 맡기지 않고 미리 모아서 알려준다</b> — 격자로 40석을 넣다가
 * 중간에 터지면 어디까지 들어갔는지 알 수 없고, 제약 위반은 화면에 이유가 안 보인다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SeatMasterAdminService {

    /** 한 번에 만들 수 있는 좌석 수. 오타(행 100 × 열 100)로 만 건이 들어가는 것을 막는다. */
    private static final int MAX_GRID_SIZE = 500;

    private final SeatMasterRepository seatMasterRepository;
    private final SeatAssignmentRepository seatAssignmentRepository;
    private final StudyAreaAdminService studyAreaAdminService;

    public List<SeatMaster> list(AuthPrincipal me, Long studyAreaId) {
        studyAreaAdminService.load(me, studyAreaId);
        return seatMasterRepository.findByStudyAreaId(studyAreaId);
    }

    /** 좌석 단건 등록. 배치도에서 한 자리만 끼워 넣을 때 쓴다. */
    @Transactional
    public SeatMaster create(AuthPrincipal me, Long studyAreaId, String seatCd, String seatNm,
                             int xPos, int yPos) {
        StudyArea area = studyAreaAdminService.load(me, studyAreaId);
        Map<String, SeatMaster> existing = existingByCd(area, List.of(seatCd));
        rejectIfOccupied(existing, List.of(seatCd));
        return persist(area, existing, seatCd, seatNm == null ? seatCd : seatNm, xPos, yPos);
    }

    /**
     * 격자 일괄 등록.
     *
     * <p><b>전부-아니면-전무다.</b> 하나라도 코드가 겹치면 겹친 코드를 <b>전부 모아</b>
     * 알려주고 아무것도 만들지 않는다 — 절반만 들어간 배치도는 지우고 다시 만드는 것보다
     * 손이 더 간다.
     */
    @Transactional
    public List<SeatMaster> createGrid(AuthPrincipal me, SeatGridSpec spec) {
        StudyArea area = studyAreaAdminService.load(me, spec.studyAreaId());

        List<PlannedSeat> planned = plan(spec);
        List<String> codes = planned.stream().map(PlannedSeat::seatCd).toList();

        Map<String, SeatMaster> existing = existingByCd(area, codes);
        rejectIfOccupied(existing, codes);

        List<SeatMaster> created = new ArrayList<>(planned.size());
        for (PlannedSeat p : planned) {
            created.add(persist(area, existing, p.seatCd(), p.seatCd(), p.xPos(), p.yPos()));
        }
        return created;
    }

    /** 이름·좌표 수정. {@code seatCd}는 대상이 아니다(키오스크가 이 코드로 좌석을 찾는다). */
    @Transactional
    public SeatMaster update(AuthPrincipal me, Long seatId, String seatNm, Integer xPos,
                             Integer yPos) {
        SeatMaster seat = load(me, seatId);
        seat.update(seatNm, xPos, yPos);
        return seat;
    }

    /**
     * 좌석 삭제(soft).
     *
     * <p><b>물리 삭제하지 않는다</b> — {@code seat_assignment}가 이 행을 참조하므로 지우면
     * 과거 배정 이력이 끊긴다. <b>배정 중인 좌석은 거부한다</b>: 지워도 배정 행은 살아 있어
     * "없는 좌석에 앉아 있는 학생"이 남는다.
     */
    @Transactional
    public void delete(AuthPrincipal me, Long seatId) {
        SeatMaster seat = load(me, seatId);
        if (seatAssignmentRepository.findActiveBySeatId(seatId).isPresent()) {
            throw new BusinessException(ErrorCode.SEAT_ALREADY_OCCUPIED,
                    "배정된 좌석은 삭제할 수 없습니다. 배정을 먼저 해제하세요.");
        }
        seat.markDeleted();
    }

    private SeatMaster load(AuthPrincipal me, Long seatId) {
        SeatMaster seat = seatMasterRepository.findDetailById(seatId)
                .orElseThrow(() -> new BusinessException(ErrorCode.SEAT_NOT_FOUND));
        if (!me.canAccessAcademy(seat.getAcademy().getId())) {
            throw new BusinessException(ErrorCode.OTHER_BRANCH_ACCESS_DENIED);
        }
        return seat;
    }

    // ── 코드 충돌 ────────────────────────────────────────────────────────────

    private Map<String, SeatMaster> existingByCd(StudyArea area, List<String> codes) {
        Map<String, SeatMaster> map = new LinkedHashMap<>();
        seatMasterRepository
                .findAnyByAcademyIdAndSeatCdIn(area.getAcademy().getId(), codes)
                .forEach(s -> map.put(s.getSeatCd(), s));
        return map;
    }

    /**
     * 살아 있는 좌석과 겹치면 거부한다.
     *
     * <p><b>겹친 코드를 전부 모아서 한 번에 알려준다.</b> 하나씩 알려주면 사용자가 격자를
     * 고쳐 올릴 때마다 충돌을 한 건씩만 발견하게 된다. 삭제분과 겹치는 것은 충돌이 아니라
     * <b>되살리기</b>다({@link #persist}).
     */
    private void rejectIfOccupied(Map<String, SeatMaster> existing, List<String> codes) {
        List<String> conflicts = codes.stream()
                .map(existing::get)
                .filter(s -> s != null && !s.isDeleted())
                .map(SeatMaster::getSeatCd)
                .toList();
        if (conflicts.isEmpty()) {
            return;
        }
        throw new BusinessException(ErrorCode.SEAT_CD_DUPLICATED,
                "같은 지점에 이미 있는 좌석번호입니다: " + String.join(", ", conflicts)
                        + " (좌석번호는 지점 안에서 유일해야 합니다. 별관 좌석은 본관과 겹치지 않는 "
                        + "번호대를 쓰세요.)");
    }

    private SeatMaster persist(StudyArea area, Map<String, SeatMaster> existing, String seatCd,
                               String seatNm, int xPos, int yPos) {
        SeatMaster deleted = existing.get(seatCd);
        if (deleted != null) {
            // 지워진 좌석의 코드도 유니크 제약을 붙들고 있어 새 행을 넣을 수 없다
            deleted.reviveAs(area, seatNm, xPos, yPos);
            return deleted;
        }
        return seatMasterRepository.save(
                new SeatMaster(area.getAcademy(), area, seatCd, seatNm, xPos, yPos));
    }

    // ── 격자 계산 ────────────────────────────────────────────────────────────

    private List<PlannedSeat> plan(SeatGridSpec spec) {
        if (spec.rows() < 1 || spec.columns() < 1) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "행·열은 1 이상이어야 합니다.");
        }
        if (spec.rows() * spec.columns() > MAX_GRID_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST,
                    "한 번에 만들 수 있는 좌석은 " + MAX_GRID_SIZE + "개까지입니다.");
        }

        Set<String> skips = new HashSet<>();
        if (spec.skips() != null) {
            spec.skips().forEach(s -> skips.add(s.row() + ":" + s.column()));
        }

        int number = spec.startNumber();
        List<PlannedSeat> planned = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // 세로 우선이면 열을 먼저 훑는다. 번호는 빈 칸을 건너뛰고 이어진다
        int outer = spec.columnMajor() ? spec.columns() : spec.rows();
        int inner = spec.columnMajor() ? spec.rows() : spec.columns();
        for (int o = 1; o <= outer; o++) {
            for (int i = 1; i <= inner; i++) {
                int row = spec.columnMajor() ? i : o;
                int column = spec.columnMajor() ? o : i;
                if (skips.contains(row + ":" + column)) {
                    continue;
                }
                String seatCd = spec.seatCdPrefix() + pad(number++, spec.numberPadding());
                if (!seen.add(seatCd)) {
                    // 접두어·시작번호 조합이 스스로 겹치면 DB 제약보다 먼저 잡는다
                    throw new BusinessException(ErrorCode.SEAT_CD_DUPLICATED,
                            "생성될 좌석번호가 서로 겹칩니다: " + seatCd);
                }
                planned.add(new PlannedSeat(seatCd,
                        spec.startX() + column - 1,
                        spec.startY() + row - 1));
            }
        }
        if (planned.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "만들 좌석이 없습니다.");
        }
        return planned;
    }

    private String pad(int number, int width) {
        String s = String.valueOf(number);
        return s.length() >= width ? s : "0".repeat(width - s.length()) + s;
    }

    private record PlannedSeat(String seatCd, int xPos, int yPos) {
    }

    /**
     * 격자 등록 입력.
     *
     * @param seatCdPrefix  좌석번호 접두어. {@code "A-"} + 번호로 {@code A-01}이 된다
     * @param startNumber   시작 번호. 이어붙이기(41번부터)를 위해 지정할 수 있다
     * @param numberPadding 번호 자릿수. {@code 2}면 {@code 01}
     * @param startX        좌표 시작값. 기존 격자 옆에 붙일 때 쓴다
     * @param columnMajor   세로 우선 번호매김
     * @param skips         통로 등 좌석이 없는 칸(행·열은 1부터)
     */
    public record SeatGridSpec(
            Long studyAreaId,
            int rows,
            int columns,
            String seatCdPrefix,
            int startNumber,
            int numberPadding,
            int startX,
            int startY,
            boolean columnMajor,
            List<GridSkip> skips) {
    }

    /** 격자에서 비워 둘 칸. */
    public record GridSkip(int row, int column) {
    }
}
