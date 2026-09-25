-- 질의응답 대면 예약의 과목 (0803 회신 "질의응답 사진첨부·과목")
--
-- 선생님이 어느 과목을 준비할지 알아야 한다. 자유 입력이다 — 과목 목록이 학년·선택과목마다
-- 달라 마스터로 묶으면 없는 과목을 못 고른다. 사진은 첨부(file_attachment)로 붙는다.
ALTER TABLE qna_offline_reservation
    ADD COLUMN subject VARCHAR(30);
