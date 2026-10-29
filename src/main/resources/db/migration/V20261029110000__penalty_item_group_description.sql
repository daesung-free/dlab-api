ALTER TABLE penalty_item
    ADD COLUMN group_name VARCHAR(100),
    ADD COLUMN description VARCHAR(500);

COMMENT ON COLUMN penalty_item.group_name IS '상벌점 화면 분류';
COMMENT ON COLUMN penalty_item.description IS '상벌점 항목 설명 및 적용 기준';
