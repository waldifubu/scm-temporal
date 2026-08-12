TRUNCATE TABLE users_roles;
DELETE FROM product_categories;

DELETE FROM roles;
DELETE FROM users;
DELETE FROM categories;

-- DELETE FROM order_items;
-- DELETE FROM orders;

/* Roles */
INSERT INTO roles (id, rolename) VALUES (1,'CUSTOMER');
INSERT INTO roles (id, rolename) VALUES (2,'MANAGER');
INSERT INTO roles (id, rolename) VALUES (3,'SUPPLIER');
INSERT INTO roles (id, rolename) VALUES (4,'WAREHOUSE');
INSERT INTO roles (id, rolename) VALUES (5,'LOGISTICS');
INSERT INTO roles (id, rolename) VALUES (6,'DISTRIBUTOR');
INSERT INTO roles (id, rolename) VALUES (7,'ADMIN');


/* Users */
/*
INSERT INTO users (user_type,color,created_at,email,first_name,is_active,last_login,last_name,password,updated_at,username) VALUES
                        ('customer','yellow','2026-07-06 23:46:05.000','customer1@customer.com','Cuddy',1,'2026-08-09 22:38:44.561','Customer VW','{argon2}$argon2id$v=19$m=16384,t=2,p=1$lzmGDfnAJrS+OUmiIQeUFw$nLOhDHiTQNGLdQf8WEViD4IFtoeLmJNcZ+j5eXGPywk','2026-08-09 22:38:44.590','customer1'),
                        ('customer','#FFDC0F','2026-07-06 23:46:05.000','customer2@customer.com','Cuddy',1,'2026-07-15 11:14:43.254','Customer Toyota','{argon2}$argon2id$v=19$m=16384,t=2,p=1$FXPVFmJx9teA/5pKkdj0sA$jR7jVqr1zPhAaabQi3CkgYu25AacK87b5Zz+jt8LQfw','2026-07-15 11:14:43.286','customer2'),
                        ('manager','#64BE28','2026-07-06 23:46:05.000','manager1@manager.com','Manny',1,'2026-07-15 11:53:37.903','Manager','{argon2}$argon2id$v=19$m=16384,t=2,p=1$0YA8iu73oh77DgQJKkkwoQ$XVGnHvq0yV9Ipseu9IKrE8YVrMVgs8aaRCEuGEVgtyI','2026-07-15 11:53:37.934','manager1'),
                        ('manager','#35FF6B','2026-07-06 23:46:05.000','manager2@manager.com','Mandy',1,NULL,'Manager','{argon2}$argon2id$v=19$m=16384,t=2,p=1$im2k1dwkSVHCrDbIAuSDaA$+MFdSYXpfU7ZyhlOKeoZErQTaNU8Hi8f/PwOiRE3XAI','2026-07-06 23:46:05.025','manager2'),
                        ('supplier','#0DBEDC','2026-07-06 23:46:05.000','supplier1@supplier.com','Samson',1,NULL,'Supplier','{argon2}$argon2id$v=19$m=16384,t=2,p=1$ndO5GYkjw1nKLSCXfkvFcQ$kPJzGsGtdTnLXf7T1Aacs8JIXHf40VHMyWcgxb8TGAA','2026-07-06 23:46:05.116','supplier1'),
                        ('supplier','#0F9BFF','2026-07-06 23:46:05.000','supplier2@supplier.com','Susan',1,NULL,'Supplier','{argon2}$argon2id$v=19$m=16384,t=2,p=1$L6xCbZDoBkqvLG+RftlxHg$7CjoBmjRVCsarT+jXCYzvHe/h1QScN4GkOO7wafGSQ4','2026-07-06 23:46:05.199','supplier2'),
                        ('warehouse','#9C27B0','2026-07-06 23:46:05.000','warehouse1@warehouse.com','Walter',1,NULL,'Warehouse','{argon2}$argon2id$v=19$m=16384,t=2,p=1$rFHNciQUkc01v151BAtR6w$qz+s0+T5uGuegddlo+6a8nvBsxKcTIbUz7CHT27hYCY','2026-07-06 23:46:05.276','warehouse1'),
                        ('logistics','#0D6EFD','2026-07-06 23:46:05.000','logistics1@logistics.com','Luther',1,NULL,'Logistics','{argon2}$argon2id$v=19$m=16384,t=2,p=1$z0Lmh4t2yAfluq1riR/I8A$LgWK7DBrffLhAxACtd11LOAbJTa4Wn0mHbv+DDqduwg','2026-07-06 23:46:05.373','logistics1'),
                        ('distributor','#FA9600','2026-07-06 23:46:05.000','distributor1@distributor.com','Daniel',1,NULL,'Distributor DHL','{argon2}$argon2id$v=19$m=16384,t=2,p=1$cQnWDU12E8r1vxWS7iONfg$9H1Plnr38/xuelKtsJ96fTZXqFeIahLuymefIPmnQJc','2026-07-06 23:46:05.453','distributor1'),
                        ('distributor','#FA96008F','2026-07-06 23:46:06.000','distributor2@distributor.com','Dustin',1,NULL,'Distributor Nagel','{argon2}$argon2id$v=19$m=16384,t=2,p=1$sKuDWspzoEwzvz2sRxqQ3Q$KaVoLBeS+xcv4O9Qnzj5WYO1j08B1MMMYcn4bqu8C40','2026-07-06 23:46:05.529','distributor2'),
                        ('admin','#C80019','2026-07-06 23:46:06.000','admin1@admin.com','Anton',1,NULL,'Admin','{argon2}$argon2id$v=19$m=16384,t=2,p=1$jlvmW2qiZo0TF/rgoJtBhg$I2HWH0i1XyQBxe1tjOiq8l7Y4uSDuDJe2g5GSWU2x/M','2026-07-06 23:46:05.629','admin1'),
                        ('customer',NULL,'2026-07-14 01:02:09.000','claude-test@example.com','Claude',1,NULL,'Test','{argon2}$argon2id$v=19$m=16384,t=2,p=1$mbZM7RiiWz7TXOsg0kISSQ$kz68QpKbP7oaYC58g4keJBa4913DQpMWHCIbaYZPE70','2026-07-14 01:02:09.929','claude-test'),
                        ('customer','#ccDDcc','2026-07-14 15:59:58.000','wfubu@web.de9','Waldi',1,NULL,'Dell','{argon2}$argon2id$v=19$m=16384,t=2,p=1$85ul6QgwOjvFzQ/TcYATBQ$E0+xeydtscoZMLwBpNK/fsczGnQp5tQcwxdrDejvPxM','2026-07-14 15:59:58.549','waldifubu9');
*/
/* Users - Roles */
/*
INSERT INTO users_roles (user_id,role_id) VALUES (1,1), (2,1), (12,1), (13,1), (3,2), (4,2), (5,3), (6,3), (7,4), (8,5), (9,6),(10,6), (11,7);
*/

/* Categories */
INSERT INTO categories (id, name, description) VALUES (1, 'Interieur', 'Interior components');
INSERT INTO categories (id, name, description) VALUES (2, 'Exterieur', 'Exterior components');
INSERT INTO categories (id, name, description) VALUES (3, 'Cockpuit', 'Cockpit components');
INSERT INTO categories (id, name, description) VALUES (4, 'Drive', 'Drive components');
INSERT INTO categories (id, name, description) VALUES (5, 'Engine', 'Engine components');

DELETE FROM components;
TRUNCATE TABLE stocks;
DELETE FROM products;

-- TRUNCATE TABLE storehouses;
-- DELETE FROM products;

/*  Products */
SET @P1 = UUID();
SET @P2 = UUID();
SET @P3 = UUID();
SET @P4 = UUID();

INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at, sku) VALUES(1, 1001, 'Front left door', 19.99, 13.2, 'Front left door at driver side', NOW(), NOW(), @P1);
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at, sku) VALUES(2, 1002, 'Front right door', 19.99, 14.5, 'Front right door at passenger side', NOW(), NOW(), @P2);
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at, sku) VALUES(3, 1003, 'Rear left door', 19.99, 15.1, 'Rear left door (back)', NOW(), NOW(), @P3);
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at, sku) VALUES(4, 1004, 'Rear right door', 19.99, 17.3, 'Rear right door (back)', NOW(), NOW(), @P4);

INSERT INTO product_categories (product_id, category_id) VALUES(1, 2);
INSERT INTO product_categories (product_id, category_id) VALUES(2, 2);
INSERT INTO product_categories (product_id, category_id) VALUES(3, 2);
INSERT INTO product_categories (product_id, category_id) VALUES(4, 2);

/* Components - Products must exist before */
SET @C1 = UUID();
SET @C2  = UUID();
SET @C3  = UUID();
SET @C4  = UUID();
SET @C5  = UUID();
SET @C6  = UUID();
SET @C7  = UUID();
SET @C8  = UUID();
SET @C9  = UUID();
SET @C10  = UUID();
SET @C11  = UUID();
SET @C12  = UUID();
SET @C13  = UUID();
SET @C14  = UUID();
SET @C15  = UUID();
SET @C16  = UUID();

INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'VL-BA', 'VL-BA-001', @C1, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Gut und Billig', 'Blech innen', 'VL-BI', 'VL-BI-001', @C2, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Röchling', 'Verkleidung', 'VL-VK', 'VL-VK-001', @C3, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Bose', 'Lautsprecher', 'VL-LS', 'VL-LS-001', @C4, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'VR-BA', 'VR-BA-001', @C5, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Gut und billig', 'Blech innen', 'VR-BI', 'VR-BI-77', @C6, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Igus', 'Verkleidung', 'VR-VK', 'VR-VK-001', @C7, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Bang & Olufsen', 'Lautsprecher', 'VR-LS', 'VR-LS-655', @C8, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'HL-BA', 'HL-BA-232', @C9, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Gut und billig', 'Blech innen', 'HL-BI', 'HL-BI-123', @C10, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('ISE', 'Verkleidung', 'HL-VK', 'HL-VK-001', @C11, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('JBL', 'Lautsprecher', 'HL-LS', 'HL-LS-001', @C12, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'HR-BA', 'HR-BA-001', @C13, 4);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Gut und billig', 'Blech innen', 'HR-BI', 'HR-BI-001', @C14, 4);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('ISE', 'Verkleidung', 'HR-VK', 'HR-VK-001', @C15, 4);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Harman Kardon', 'Lautsprecher', 'HR-LS', 'HR-LS-001', @C16, 4);

/* Warehouses - Different warehouses */
REPLACE INTO storehouses (id, name, address, city, country) values (1, 'Zentrallager Hamburg', 'Hamburgser Str. 1313', 'Hamburg', 'DE');
REPLACE INTO storehouses (id, name, address, city, country) values (2, 'Aussenlager München', 'Münchener Str. 1414', 'München', 'DE');
REPLACE INTO storehouses (id, name, address, city, country) values (3, 'Sammellager Berlin', 'Berliner Str. 1515', 'Berlin', 'DE');

DROP FUNCTION IF EXISTS GetRandom1To3;

-- DELIMITER $$

CREATE FUNCTION GetRandom1To3()
    RETURNS INT
    NOT DETERMINISTIC
    NO SQL
    RETURN FLOOR(1 + RAND() * 3);
-- END$$

-- DELIMITER ;

ALTER TABLE stocks
    MODIFY COLUMN updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP;
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(1, 7, 0, @C1, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(2, 9, 0, @C2, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(3, 5, 0, @C3, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(4, 5, 0, @C4, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(5, 2, 0, @C5, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(6, 3, 0, @C6, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(7, 3, 0, @C7, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(8, 4, 0, @C8, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(9, 7, 0, @C9, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(10, 3, 0, @C10, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(11, 1, 0, @C11, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(12, 1, 0, @C12, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(13, 2, 0, @C13, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(14, 3, 0, @C14, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(15, 2, 0, @C15, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(16, 6, 0, @C16, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(17, 4, 0, @P1, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(18, 3, 0, @P2, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(19, 2, 0, @P3, GetRandom1To3());
INSERT INTO stocks (id, on_hand, reserved, sku, storehouse_id) VALUES(20, 1, 0, @P4, GetRandom1To3());