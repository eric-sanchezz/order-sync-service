CREATE TABLE customer_mapping (
    shop_customer_id  BIGINT PRIMARY KEY,
    erp_customer_code VARCHAR(50) NOT NULL
);

-- Demo data (invented customers).
INSERT INTO customer_mapping (shop_customer_id, erp_customer_code) VALUES
    (42, 'CLI-0042'),
    (77, 'CLI-0077'),
    (105, 'CLI-0105');
