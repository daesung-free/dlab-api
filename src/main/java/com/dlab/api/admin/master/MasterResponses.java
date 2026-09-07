package com.dlab.api.admin.master;

import com.dlab.domain.master.entity.*;

import java.math.BigDecimal;
import java.util.List;

public final class MasterResponses {

    private MasterResponses() {
    }

    public record Department(Long id, Long academyId, short year, String name,
                            String code, String memo, boolean active) {
        public static Department from(DepartmentMaster d) {
            return new Department(d.getId(), d.getAcademy().getId(), d.getYear(), d.getName(),
                    d.getCode(), d.getMemo(), d.isActive());
        }
    }

    /**
     * 강의실. <b>자습 구역과 다르다</b> — 자습 구역은 좌석이 속하는 단위이고 키오스크 계약에
     * 걸려 있다. 여기는 수업 공간이라 키오스크와 무관하다.
     */
    public record Room(Long id, Long academyId, String roomNo, String name, Short capacity,
                       String memo, boolean active) {
        public static Room from(com.dlab.domain.master.entity.RoomMaster r) {
            return new Room(r.getId(), r.getAcademy().getId(), r.getRoomNo(), r.getName(),
                    r.getCapacity(), r.getMemo(), r.isActive());
        }
    }

    /**
     * 계열. 지점·연도가 없다 — 전 지점 공통이라 코드도 전체에서 유일하다.
     */
    public record Track(Long id, String name, String code, String memo, boolean active) {
        public static Track from(TrackMaster t) {
            return new Track(t.getId(), t.getName(), t.getCode(), t.getMemo(), t.isActive());
        }
    }

    public record Locker(Long id, String lockerNo, Long enrollmentId, String studentName) {
        public static Locker from(LockerMaster l) {
            return new Locker(
                    l.getId(), l.getLockerNo(),
                    l.getAssignedEnrollment() == null ? null : l.getAssignedEnrollment().getId(),
                    l.getAssignedEnrollment() == null ? null : l.getAssignedEnrollment().getStudentName());
        }
    }

    /**
     * 블록 일괄 등록 결과.
     *
     * @param skipped 이미 있어서 건너뛴 번호. <b>화면이 이걸 반드시 알려야 한다</b> —
     *                120칸을 만들었는데 3칸이 이미 있었다는 걸 모르면 운영자는 전부
     *                새로 생긴 줄 안다
     */
    public record LockerBlock(List<Locker> created, List<String> skipped) {
        public static LockerBlock from(
                com.dlab.domain.master.service.MasterDataService.LockerBlockOutcome outcome) {
            return new LockerBlock(outcome.created().stream().map(Locker::from).toList(),
                    outcome.skipped());
        }
    }

    public record ScholarshipItem(Long id, Long enrollmentId, String scholarshipType, BigDecimal discountRate) {
        public static ScholarshipItem from(Scholarship s) {
            return new ScholarshipItem(s.getId(), s.getEnrollment().getId(),
                    s.getScholarshipType(), s.getDiscountRate());
        }
    }

    /**
     * 장학 종류 마스터 한 줄.
     *
     * <p>{@code code}가 취소 규칙과 이어지는 값이다 — 화면에 반드시 노출할 것.
     * 이름만 보이면 규칙이 왜 안 걸리는지 데스크가 알 수 없다.
     */
    public record ScholarshipMasterItem(Long id, Long academyId, short year, String code,
                                        String name, BigDecimal discountRate, boolean active,
                                        short sortOrder, String memo) {
        public static ScholarshipMasterItem from(
                com.dlab.domain.master.entity.ScholarshipMaster m) {
            return new ScholarshipMasterItem(m.getId(),
                    m.isCommon() ? null : m.getAcademy().getId(), m.getYear(),
                    m.getCode(), m.getName(), m.getDiscountRate(), m.isActive(),
                    m.getSortOrder(), m.getMemo());
        }
    }

    public record CourseType(Long id, Long academyId, short year, String name, short sortOrder,
                            String code, String memo, boolean active) {
        public static CourseType from(com.dlab.domain.master.entity.CourseType c) {
            return new CourseType(c.getId(), c.getAcademy().getId(), c.getYear(),
                    c.getName(), c.getSortOrder(), c.getCode(), c.getMemo(), c.isActive());
        }
    }




    public record Curriculum(Long id, short year, String name, Long classId,
                             String className, short sortOrder,
                             String code, String memo, boolean active) {
        public static Curriculum from(com.dlab.domain.master.entity.Curriculum c) {
            return new Curriculum(c.getId(), c.getYear(), c.getName(),
                    c.getClassMaster() == null ? null : c.getClassMaster().getId(),
                    c.getClassMaster() == null ? null : c.getClassMaster().getName(),
                    c.getSortOrder(), c.getCode(), c.getMemo(), c.isActive());
        }
    }

    public record Tuition(Long id, short year, String name, int amount, short sortOrder,
                         String code, String memo, boolean active) {
        public static Tuition from(com.dlab.domain.master.entity.Tuition t) {
            return new Tuition(t.getId(), t.getYear(), t.getName(), t.getAmount(),
                    t.getSortOrder(), t.getCode(), t.getMemo(), t.isActive());
        }
    }

}
