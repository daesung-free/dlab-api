package com.dlab.api.admin.master;

import com.dlab.domain.master.entity.*;

import java.math.BigDecimal;

public final class MasterResponses {

    private MasterResponses() {
    }

    public record Department(Long id, Long academyId, short year, String name) {
        public static Department from(DepartmentMaster d) {
            return new Department(d.getId(), d.getAcademy().getId(), d.getYear(), d.getName());
        }
    }

    public record Track(Long id, String name) {
        public static Track from(TrackMaster t) {
            return new Track(t.getId(), t.getName());
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

    public record ScholarshipItem(Long id, Long enrollmentId, String scholarshipType, BigDecimal discountRate) {
        public static ScholarshipItem from(Scholarship s) {
            return new ScholarshipItem(s.getId(), s.getEnrollment().getId(),
                    s.getScholarshipType(), s.getDiscountRate());
        }
    }

    public record CourseType(Long id, Long academyId, short year, String name, short sortOrder) {
        public static CourseType from(com.dlab.domain.master.entity.CourseType c) {
            return new CourseType(c.getId(), c.getAcademy().getId(), c.getYear(),
                    c.getName(), c.getSortOrder());
        }
    }




    public record Curriculum(Long id, short year, String name, Long classId,
                             String className, short sortOrder) {
        public static Curriculum from(com.dlab.domain.master.entity.Curriculum c) {
            return new Curriculum(c.getId(), c.getYear(), c.getName(),
                    c.getClassMaster() == null ? null : c.getClassMaster().getId(),
                    c.getClassMaster() == null ? null : c.getClassMaster().getName(),
                    c.getSortOrder());
        }
    }

    public record Tuition(Long id, short year, String name, int amount, short sortOrder) {
        public static Tuition from(com.dlab.domain.master.entity.Tuition t) {
            return new Tuition(t.getId(), t.getYear(), t.getName(), t.getAmount(), t.getSortOrder());
        }
    }

}
