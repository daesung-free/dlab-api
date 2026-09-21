-- 반 강의실 (관리자 웹 반 목록의 "강의실" 칸)
--
-- 강의실 마스터(room_master)는 V2 부터 있었는데 반과 이어지지 않았다.
-- 반마다 하나 — 이동수업처럼 반이 강의실을 바꿔 쓰는 것은 시간표 영역이라 여기서 다루지 않는다.
ALTER TABLE class_master
    ADD COLUMN room_id BIGINT REFERENCES room_master (id);
