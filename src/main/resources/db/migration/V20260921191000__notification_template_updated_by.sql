-- 알림 템플릿 수정자 (관리자 웹 "마지막 수정" 칸)
--
-- 문구는 알림톡 심사와 직결돼 "누가 바꿨나" 를 물을 일이 생긴다. created_by 는 등록자라
-- 이후 수정자를 남기지 못한다. 기존 행은 수정 이력이 없으므로 비워 둔다.
ALTER TABLE notification_template
    ADD COLUMN updated_by BIGINT;
