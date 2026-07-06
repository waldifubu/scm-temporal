DELETE FROM users_roles;
DELETE FROM product_categories;
DELETE FROM components;

DELETE FROM roles;
DELETE FROM users;
DELETE FROM categories;
DELETE FROM storehouses;
DELETE FROM products;

/* Roles */
INSERT INTO roles (id,name) VALUES (1,'ROLE_CUSTOMER');
INSERT INTO roles (id,name) VALUES (2,'ROLE_MANAGER');
INSERT INTO roles (id,name) VALUES (3,'ROLE_SUPPLIER');
INSERT INTO roles (id,name) VALUES (4,'ROLE_WAREHOUSE');
INSERT INTO roles (id,name) VALUES (5,'ROLE_LOGISTICS');
INSERT INTO roles (id,name) VALUES (6,'ROLE_DISTRIBUTOR');
INSERT INTO roles (id,name) VALUES (7,'ROLE_ADMIN');

/* Categories */
INSERT INTO categories (id, name) VALUES (1, 'Interieur');
INSERT INTO categories (id, name) VALUES (2, 'Exterieur');
INSERT INTO categories (id, name) VALUES (3, 'Cockpuit');
INSERT INTO categories (id, name) VALUES (4, 'Drive');
INSERT INTO categories (id, name) VALUES (5, 'Engine');

/*  Products */
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at) VALUES(1, 1001, 'Front left door', 19.99, 13.2, 'Front left door at driver side', NOW(), NOW());
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at) VALUES(2, 1002, 'Front right door', 19.99, 14.5, 'Front right door at passenger side', NOW(), NOW());
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at) VALUES(3, 1003, 'Rear left door', 19.99, 15.1, 'Rear left door (back)', NOW(), NOW());
INSERT INTO products (id, article_no, name, unit_price, weight, description, created_at, updated_at) VALUES(4, 1004, 'Rear right door', 19.99, 17.3, 'Rear right door (back)', NOW(), NOW());

INSERT INTO product_categories (product_id, category_id) VALUES(1, 2);
INSERT INTO product_categories (product_id, category_id) VALUES(2, 2);
INSERT INTO product_categories (product_id, category_id) VALUES(3, 2);
INSERT INTO product_categories (product_id, category_id) VALUES(4, 2);

/* Components - Products must exist before */
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'VL-BA', 'VL-BA-001', 1);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('gut und billig', 'Blech innen', 'VL-BI', 'VL-BI-001', 1);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Röchling', 'Verkleidung', 'VL-VK', 'VL-VK-001', 1);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Bose', 'Lautsprecher', 'VL-LS', 'VL-LS-001', 1);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'VR-BA', 'VR-BA-001', 2);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('gut und billig', 'Blech innen', 'VR-BI', 'VR-BI-77', 2);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Igus', 'Verkleidung', 'VR-VK', 'VR-VK-001', 2);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Bang & Olufsen', 'Lautsprecher', 'VR-LS', 'VR-LS-655', 2);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'HL-BA', 'HL-BA-232', 3);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('gut und billig', 'Blech innen', 'HL-BI', 'HL-BI-123', 3);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('ISE', 'Verkleidung', 'HL-VK', 'HL-VK-001', 3);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('JBL', 'Lautsprecher', 'HL-LS', 'HL-LS-001', 3);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('Pfusch und weg', 'Blech außen', 'HR-BA', 'HR-BA-001', 4);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('gut und billig', 'Blech innen', 'HR-BI', 'HR-BI-001', 4);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('ISE', 'Verkleidung', 'HR-VK', 'HR-VK-001', 4);
INSERT INTO components (manufacturer, name, article_no, sku, product_id) VALUES('harmann kardon', 'Lautsprecher', 'HR-LS', 'HR-LS-001', 4);

/* Warehouses - Different warehouses */
insert into storehouses (id, name, address, city, country) values (1, 'Zentrallager Hamburg', 'Hamburgser Str. 1313', 'Hamburg', 'DE');
insert into storehouses (id, name, address, city, country) values (2, 'Aussenlager München', 'Münchener Str. 1414', 'München', 'DE');