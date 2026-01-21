/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

CREATE TABLE IF NOT EXISTS users
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,
    first_name    TEXT,
    last_name     TEXT,
    created_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_login    TIMESTAMP
);

CREATE TABLE IF NOT EXISTS products
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    name           TEXT    NOT NULL,
    description    TEXT,
    price          REAL    NOT NULL,
    sku            TEXT    NOT NULL UNIQUE,
    stock_quantity INTEGER NOT NULL DEFAULT 0,
    category_id    INTEGER,
    supplier_id    INTEGER
);

CREATE TABLE IF NOT EXISTS customers
(
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name        TEXT NOT NULL,
    last_name         TEXT NOT NULL,
    email             TEXT NOT NULL UNIQUE,
    phone_number      TEXT,
    registration_date DATE,
    customer_segment  TEXT,
    notes             TEXT
);

CREATE TABLE IF NOT EXISTS orders
(
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id         INTEGER,
    order_date          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    status              TEXT NOT NULL,
    total_amount        REAL NOT NULL,
    shipping_address_id INTEGER,
    billing_address_id  INTEGER,
    shipper_id          INTEGER
);

CREATE TABLE IF NOT EXISTS order_items
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id    INTEGER,
    product_id  INTEGER,
    quantity    INTEGER NOT NULL,
    unit_price  REAL    NOT NULL,
    discount    REAL DEFAULT 0.00,
    total_price REAL    NOT NULL
);

CREATE TABLE IF NOT EXISTS employees
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    first_name    TEXT NOT NULL,
    last_name     TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,
    job_title     TEXT,
    hire_date     DATE,
    salary        REAL,
    department_id INTEGER
);

CREATE TABLE IF NOT EXISTS departments
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    name            TEXT NOT NULL,
    description     TEXT,
    manager_id      INTEGER,
    location        TEXT,
    budget          REAL,
    creation_date   DATE,
    phone_extension TEXT
);

CREATE TABLE IF NOT EXISTS addresses
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id    INTEGER,
    street_address TEXT NOT NULL,
    city           TEXT NOT NULL,
    state          TEXT,
    postal_code    TEXT NOT NULL,
    country        TEXT NOT NULL,
    address_type   TEXT
);

CREATE TABLE IF NOT EXISTS categories
(
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    name               TEXT NOT NULL,
    description        TEXT,
    parent_category_id INTEGER,
    image_url          TEXT,
    is_active          INTEGER DEFAULT 1,
    display_order      INTEGER,
    slug               TEXT UNIQUE
);

CREATE TABLE IF NOT EXISTS suppliers
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    name          TEXT NOT NULL,
    contact_name  TEXT,
    contact_email TEXT,
    phone_number  TEXT,
    address       TEXT,
    country       TEXT,
    website       TEXT
);

CREATE TABLE IF NOT EXISTS shippers
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    name             TEXT NOT NULL,
    phone            TEXT,
    email            TEXT,
    contact_person   TEXT,
    service_area     TEXT,
    tracking_website TEXT,
    is_active        INTEGER DEFAULT 1
);

CREATE TABLE IF NOT EXISTS invoices
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    order_id       INTEGER,
    invoice_date   DATE NOT NULL,
    due_date       DATE NOT NULL,
    total_amount   REAL NOT NULL,
    status         TEXT NOT NULL,
    payment_method TEXT,
    notes          TEXT
);

CREATE TABLE IF NOT EXISTS payments
(
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    invoice_id     INTEGER,
    payment_date   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    amount         REAL NOT NULL,
    payment_method TEXT,
    transaction_id TEXT,
    status         TEXT NOT NULL,
    notes          TEXT
);

CREATE TABLE IF NOT EXISTS reviews
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    product_id    INTEGER,
    customer_id   INTEGER,
    rating        INTEGER NOT NULL,
    comment       TEXT,
    review_date   TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    is_approved   INTEGER   DEFAULT 0,
    helpful_votes INTEGER   DEFAULT 0
);

CREATE TABLE IF NOT EXISTS shopping_cart
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    customer_id INTEGER,
    product_id  INTEGER,
    quantity    INTEGER NOT NULL,
    price       REAL    NOT NULL,
    added_at    TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    session_id  TEXT,
    notes       TEXT
);

-- Sample Data
INSERT OR IGNORE INTO users (username, password_hash, email, first_name, last_name)
VALUES ('luispepe', 'hash123', 'luis.pepe@example.com', 'Luis', 'Pepe');
INSERT OR IGNORE INTO products (name, description, price, sku, stock_quantity)
VALUES ('Laptop Pro', 'A powerful laptop for professionals', 1200.00, 'LP-001', 50);
INSERT OR IGNORE INTO customers (first_name, last_name, email)
VALUES ('John', 'Doe', 'john.doe@example.com');
