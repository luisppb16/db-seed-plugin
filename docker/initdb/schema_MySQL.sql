CREATE TABLE users (
                       id CHAR(36) PRIMARY KEY,
                       username VARCHAR(50) UNIQUE,
                       email VARCHAR(100) UNIQUE,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       is_active BOOLEAN DEFAULT TRUE
);

CREATE TABLE user_profiles (
                               id CHAR(36) PRIMARY KEY,
                               user_id CHAR(36) UNIQUE,
                               full_name VARCHAR(100),
                               birth_date DATE,
                               country VARCHAR(50),
                               bio TEXT,
                               CONSTRAINT fk_user_profile FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE roles (
                       id CHAR(36) PRIMARY KEY,
                       role_name VARCHAR(50) UNIQUE,
                       description TEXT,
                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                       is_default BOOLEAN DEFAULT FALSE
);

CREATE TABLE user_roles (
                            id CHAR(36) PRIMARY KEY,
                            user_id CHAR(36),
                            role_id CHAR(36),
                            assigned_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                            assigned_by CHAR(36),
                            notes TEXT,
                            CONSTRAINT uq_user_roles UNIQUE (user_id, role_id),
                            CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES users(id),
                            CONSTRAINT fk_role FOREIGN KEY (role_id) REFERENCES roles(id),
                            CONSTRAINT fk_assigned_by FOREIGN KEY (assigned_by) REFERENCES users(id)
);

CREATE TABLE products (
                          id CHAR(36) PRIMARY KEY,
                          product_name VARCHAR(100),
                          description TEXT,
                          price DECIMAL(10, 2),
                          stock INT DEFAULT 0
);

CREATE TABLE orders (
                        id CHAR(36) PRIMARY KEY,
                        user_id CHAR(36),
                        order_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                        status VARCHAR(20),
                        total_amount DECIMAL(10, 2),
                        CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE order_items (
                             id CHAR(36) PRIMARY KEY,
                             order_id CHAR(36),
                             product_id CHAR(36),
                             quantity INT,
                             unit_price DECIMAL(10, 2),
                             discount DECIMAL(5, 2) DEFAULT 0,
                             CONSTRAINT uq_order_items UNIQUE (order_id, product_id),
                             CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES orders(id),
                             CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES products(id)
);

CREATE TABLE payments (
                          id CHAR(36) PRIMARY KEY,
                          order_id CHAR(36),
                          payment_date TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                          amount DECIMAL(10, 2),
                          method VARCHAR(20),
                          CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE TABLE shipments (
                           id CHAR(36) PRIMARY KEY,
                           order_id CHAR(36),
                           shipped_date TIMESTAMP,
                           carrier VARCHAR(50),
                           tracking_number VARCHAR(100),
                           CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES orders(id)
);

CREATE TABLE reviews (
                         id CHAR(36) PRIMARY KEY,
                         user_id CHAR(36),
                         product_id CHAR(36),
                         rating INT,
                         comment TEXT,
                         CONSTRAINT chk_rating CHECK (rating BETWEEN 1 AND 5),
                         CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES users(id),
                         CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES products(id)
);
