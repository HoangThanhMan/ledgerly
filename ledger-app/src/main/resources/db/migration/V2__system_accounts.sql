-- Account hệ thống (W02-06): đối ứng của mọi dòng tiền vào và ra khỏi hệ thống,
-- nhờ vậy I4 (tổng số dư của mọi account bằng 0) luôn đúng.
INSERT INTO accounts (type, code, currency, allow_negative) VALUES
    -- Nguồn của nạp tiền nội bộ (POST /v1/admin/deposits): âm dần khi nạp cho ví.
    ('SYSTEM', 'system:funding',             'VND', TRUE),
    -- Tiền của người dùng đang nằm ở ngân hàng: nạp qua ngân hàng làm account này âm.
    ('SYSTEM', 'system:bank-settlement',     'VND', TRUE),
    -- Tiền rút đang giữ chờ ngân hàng chi hộ: chỉ nhận rồi trả ra, không bao giờ âm.
    ('SYSTEM', 'system:withdrawal-suspense', 'VND', FALSE);
