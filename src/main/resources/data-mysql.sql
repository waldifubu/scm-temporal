DELETE FROM users_roles;
DELETE FROM product_categories;
DELETE FROM components;

DELETE FROM roles;
DELETE FROM users;
DELETE FROM categories;
DELETE FROM storehouses;
DELETE FROM products;

-- DELETE FROM components;
-- DELETE FROM order_items;
-- DELETE FROM orders;

/* Roles */
INSERT INTO roles (id, rolename) VALUES (1,'ROLE_CUSTOMER');
INSERT INTO roles (id, rolename) VALUES (2,'ROLE_MANAGER');
INSERT INTO roles (id, rolename) VALUES (3,'ROLE_SUPPLIER');
INSERT INTO roles (id, rolename) VALUES (4,'ROLE_WAREHOUSE');
INSERT INTO roles (id, rolename) VALUES (5,'ROLE_LOGISTICS');
INSERT INTO roles (id, rolename) VALUES (6,'ROLE_DISTRIBUTOR');
INSERT INTO roles (id, rolename) VALUES (7,'ROLE_ADMIN');

/* Categories */
INSERT INTO categories (id, name) VALUES (1, 'Interieur');
INSERT INTO categories (id, name) VALUES (2, 'Exterieur');
INSERT INTO categories (id, name) VALUES (3, 'Cockpuit');
INSERT INTO categories (id, name) VALUES (4, 'Drive');
INSERT INTO categories (id, name) VALUES (5, 'Engine');

TRUNCATE TABLE components;
TRUNCATE TABLE storehouses;
TRUNCATE TABLE stock;
TRUNCATE TABLE products;

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
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('gut und billig', 'Blech innen', 'VL-BI', 'VL-BI-001', @C2, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Röchling', 'Verkleidung', 'VL-VK', 'VL-VK-001', @C3, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Bose', 'Lautsprecher', 'VL-LS', 'VL-LS-001', @C4, 1);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'VR-BA', 'VR-BA-001', @C5, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('gut und billig', 'Blech innen', 'VR-BI', 'VR-BI-77', @C6, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Igus', 'Verkleidung', 'VR-VK', 'VR-VK-001', @C7, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Bang & Olufsen', 'Lautsprecher', 'VR-LS', 'VR-LS-655', @C8, 2);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'HL-BA', 'HL-BA-232', @C9, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('gut und billig', 'Blech innen', 'HL-BI', 'HL-BI-123', @C10, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('ISE', 'Verkleidung', 'HL-VK', 'HL-VK-001', @C11, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('JBL', 'Lautsprecher', 'HL-LS', 'HL-LS-001', @C12, 3);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'HR-BA', 'HR-BA-001', @C13, 4);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('gut und billig', 'Blech innen', 'HR-BI', 'HR-BI-001', @C14, 4);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('ISE', 'Verkleidung', 'HR-VK', 'HR-VK-001', @C15, 4);
INSERT INTO components (manufacturer, name, article_no, external_id, sku, product_id) VALUES('harmann kardon', 'Lautsprecher', 'HR-LS', 'HR-LS-001', @C16, 4);

/* Warehouses - Different warehouses */
INSERT INTO storehouses (id, name, address, city, country) values (1, 'Zentrallager Hamburg', 'Hamburgser Str. 1313', 'Hamburg', 'DE');
INSERT INTO storehouses (id, name, address, city, country) values (2, 'Aussenlager München', 'Münchener Str. 1414', 'München', 'DE');
INSERT INTO storehouses (id, name, address, city, country) values (3, 'Sammellager Berlin', 'Berliner Str. 1515', 'Berlin', 'DE');

DELIMITER $$

CREATE FUNCTION GetRandom1To3()
    RETURNS INT
    NOT DETERMINISTIC
    READS SQL DATA
BEGIN
RETURN FLOOR(RAND() * 3) + 1;
END$$

DELIMITER ;

INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(1, 2, 0, @C1, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(2, 3, 0, @C2, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(3, 1, 0, @C3, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(4, 5, 0, @C4, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(5, 2, 0, @C5, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(6, 3, 0, @C6, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(7, 1, 0, @C7, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(8, 4, 0, @C8, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(9, 7, 0, @C9, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(10, 3, 0, @C10, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(11, 1, 0, @C11, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(12, 1, 0, @C12, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(13, 2, 0, @C13, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(14, 3, 0, @C14, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(15, 1, 0, @C15, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(16, 4, 0, @C16, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(17, 4, 0, @P1, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(18, 3, 0, @P2, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(19, 2, 0, @P3, GetRandom1To3());
INSERT INTO stock (id, on_hand, reserved, sku, storehouse_id) VALUES(20, 1, 0, @P4, GetRandom1To3());