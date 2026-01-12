CREATE TABLE users
(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    first_name    VARCHAR(100),
    last_name     VARCHAR(100),
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login    TIMESTAMP
);

CREATE TABLE products
(
    id             INT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(255)   NOT NULL,
    description    TEXT,
    price          DECIMAL(10, 2) NOT NULL,
    sku            VARCHAR(100)   NOT NULL UNIQUE,
    stock_quantity INT            NOT NULL DEFAULT 0,
    category_id    INT,
    supplier_id    INT
);

CREATE TABLE customers
(
    id                INT AUTO_INCREMENT PRIMARY KEY,
    first_name        VARCHAR(100) NOT NULL,
    last_name         VARCHAR(100) NOT NULL,
    email             VARCHAR(255) NOT NULL UNIQUE,
    phone_number      VARCHAR(20),
    registration_date DATE,
    customer_segment  VARCHAR(50),
    notes             TEXT
);

CREATE TABLE orders
(
    id                  INT AUTO_INCREMENT PRIMARY KEY,
    customer_id         INT,
    order_date          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status              VARCHAR(50)    NOT NULL,
    total_amount        DECIMAL(10, 2) NOT NULL,
    shipping_address_id INT,
    billing_address_id  INT,
    shipper_id          INT
);

CREATE TABLE order_items
(
    id          INT AUTO_INCREMENT PRIMARY KEY,
    order_id    INT,
    product_id  INT,
    quantity    INT            NOT NULL,
    unit_price  DECIMAL(10, 2) NOT NULL,
    discount    DECIMAL(5, 2) DEFAULT 0.00,
    total_price DECIMAL(10, 2) NOT NULL
);

CREATE TABLE employees
(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    first_name    VARCHAR(100) NOT NULL,
    last_name     VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    job_title     VARCHAR(100),
    hire_date     DATE,
    salary        DECIMAL(10, 2),
    department_id INT
);

CREATE TABLE departments
(
    id              INT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(100) NOT NULL,
    description     TEXT,
    manager_id      INT,
    location        VARCHAR(255),
    budget          DECIMAL(15, 2),
    creation_date   DATE,
    phone_extension VARCHAR(10)
);

CREATE TABLE addresses
(
    id             INT AUTO_INCREMENT PRIMARY KEY,
    customer_id    INT,
    street_address VARCHAR(255) NOT NULL,
    city           VARCHAR(100) NOT NULL,
    state          VARCHAR(100),
    postal_code    VARCHAR(20)  NOT NULL,
    country        VARCHAR(100) NOT NULL,
    address_type   VARCHAR(50) -- e.g., 'shipping', 'billing'
);

CREATE TABLE categories
(
    id                 INT AUTO_INCREMENT PRIMARY KEY,
    name               VARCHAR(100) NOT NULL,
    description        TEXT,
    parent_category_id INT,
    image_url          VARCHAR(255),
    is_active          BOOLEAN DEFAULT TRUE,
    display_order      INT,
    slug               VARCHAR(100) UNIQUE
);

CREATE TABLE suppliers
(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    name          VARCHAR(255) NOT NULL,
    contact_name  VARCHAR(255),
    contact_email VARCHAR(255),
    phone_number  VARCHAR(20),
    address       VARCHAR(255),
    country       VARCHAR(100),
    website       VARCHAR(255)
);

CREATE TABLE shippers
(
    id               INT AUTO_INCREMENT PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    phone            VARCHAR(20),
    email            VARCHAR(255),
    contact_person   VARCHAR(255),
    service_area     TEXT,
    tracking_website VARCHAR(255),
    is_active        BOOLEAN DEFAULT TRUE
);

CREATE TABLE invoices
(
    id             INT AUTO_INCREMENT PRIMARY KEY,
    order_id       INT,
    invoice_date   DATE           NOT NULL,
    due_date       DATE           NOT NULL,
    total_amount   DECIMAL(10, 2) NOT NULL,
    status         VARCHAR(50)    NOT NULL, -- e.g., 'paid', 'unpaid', 'overdue'
    payment_method VARCHAR(50),
    notes          TEXT
);

CREATE TABLE payments
(
    id             INT AUTO_INCREMENT PRIMARY KEY,
    invoice_id     INT,
    payment_date   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    amount         DECIMAL(10, 2) NOT NULL,
    payment_method VARCHAR(50),
    transaction_id VARCHAR(255),
    status         VARCHAR(50)    NOT NULL, -- e.g., 'completed', 'failed', 'pending'
    notes          TEXT
);

CREATE TABLE reviews
(
    id            INT AUTO_INCREMENT PRIMARY KEY,
    product_id    INT,
    customer_id   INT,
    rating        INT NOT NULL, -- e.g., 1 to 5
    comment       TEXT,
    review_date   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_approved   BOOLEAN   DEFAULT FALSE,
    helpful_votes INT       DEFAULT 0
);

CREATE TABLE shopping_cart
(
    id          INT AUTO_INCREMENT PRIMARY KEY,
    customer_id INT,
    product_id  INT,
    quantity    INT            NOT NULL,
    price       DECIMAL(10, 2) NOT NULL,
    added_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    session_id  VARCHAR(255),
    notes       VARCHAR(255)
);

/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

-- Sample Data
INSERT INTO users (username, password_hash, email, first_name, last_name)
VALUES ('luispepe', 'hash123', 'luis.pepe@example.com', 'Luis', 'Pepe');
INSERT INTO products (name, description, price, sku, stock_quantity)
VALUES ('Laptop Pro', 'A powerful laptop for professionals', 1200.00, 'LP-001', 50);
INSERT INTO customers (first_name, last_name, email)
VALUES ('John', 'Doe', 'john.doe@example.com');
