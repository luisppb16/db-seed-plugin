/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 *  *****************************************************************************
 */

-- ============================================================================
-- USERS AND PROFILES
-- ============================================================================

CREATE TABLE public.users
(
    id         UUID PRIMARY KEY,
    username   VARCHAR(50) UNIQUE NOT NULL,
    email      VARCHAR(100) UNIQUE NOT NULL,
    created_at TIMESTAMP DEFAULT now(),
    is_active  BOOLEAN   DEFAULT TRUE
);

CREATE TABLE public.user_profiles
(
    id         UUID PRIMARY KEY,
    user_id    UUID UNIQUE NOT NULL,
    full_name  VARCHAR(100),
    birth_date DATE,
    country    VARCHAR(50),
    bio        TEXT,
    CONSTRAINT fk_user_profile FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

-- ============================================================================
-- ROLES AND PERMISSIONS
-- ============================================================================

CREATE TABLE public.roles
(
    id          UUID PRIMARY KEY,
    role_name   VARCHAR(50) UNIQUE NOT NULL,
    description TEXT,
    created_at  TIMESTAMP DEFAULT now(),
    is_default  BOOLEAN   DEFAULT FALSE
);

CREATE TABLE public.user_roles
(
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    role_id     UUID NOT NULL,
    assigned_at TIMESTAMP DEFAULT now(),
    assigned_by UUID,
    notes       TEXT,
    CONSTRAINT uq_user_roles UNIQUE (user_id, role_id),
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_role FOREIGN KEY (role_id) REFERENCES public.roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_assigned_by FOREIGN KEY (assigned_by) REFERENCES public.users (id) ON DELETE SET NULL
);

-- ============================================================================
-- PRODUCTS
-- ============================================================================

CREATE TABLE public.categories
(
    id           UUID PRIMARY KEY,
    category_name VARCHAR(100) UNIQUE NOT NULL,
    description  TEXT,
    created_at   TIMESTAMP DEFAULT now()
);

CREATE TABLE public.products
(
    id           UUID PRIMARY KEY,
    category_id  UUID,
    product_name VARCHAR(100) NOT NULL,
    description  TEXT,
    price        NUMERIC(10, 2) NOT NULL,
    stock        INT DEFAULT 0,
    created_at   TIMESTAMP DEFAULT now(),
    CONSTRAINT fk_category FOREIGN KEY (category_id) REFERENCES public.categories (id) ON DELETE SET NULL
);

-- ============================================================================
-- ORDERS AND ITEMS
-- ============================================================================

CREATE TABLE public.orders
(
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL,
    order_date   TIMESTAMP DEFAULT now(),
    status       VARCHAR(20) DEFAULT 'PENDING',
    total_amount NUMERIC(10, 2),
    notes        TEXT,
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE TABLE public.order_items
(
    id         UUID PRIMARY KEY,
    order_id   UUID NOT NULL,
    product_id UUID NOT NULL,
    quantity   INT NOT NULL,
    unit_price NUMERIC(10, 2) NOT NULL,
    discount   NUMERIC(5, 2) DEFAULT 0,
    CONSTRAINT uq_order_items UNIQUE (order_id, product_id),
    CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES public.orders (id) ON DELETE CASCADE,
    CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES public.products (id) ON DELETE CASCADE
);

-- ============================================================================
-- PAYMENTS AND SHIPMENTS
-- ============================================================================

CREATE TABLE public.payments
(
    id           UUID PRIMARY KEY,
    order_id     UUID NOT NULL,
    payment_date TIMESTAMP DEFAULT now(),
    amount       NUMERIC(10, 2) NOT NULL,
    method       VARCHAR(20) NOT NULL,
    status       VARCHAR(20) DEFAULT 'COMPLETED',
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES public.orders (id) ON DELETE CASCADE
);

CREATE TABLE public.shipments
(
    id              UUID PRIMARY KEY,
    order_id        UUID NOT NULL,
    shipped_date    TIMESTAMP,
    estimated_delivery DATE,
    carrier         VARCHAR(50),
    tracking_number VARCHAR(100),
    status          VARCHAR(20) DEFAULT 'PENDING',
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES public.orders (id) ON DELETE CASCADE
);

-- ============================================================================
-- REVIEWS AND RATINGS
-- ============================================================================

CREATE TABLE public.reviews
(
    id         UUID PRIMARY KEY,
    user_id    UUID NOT NULL,
    product_id UUID NOT NULL,
    rating     INT CHECK (rating BETWEEN 1 AND 5),
    comment    TEXT,
    created_at TIMESTAMP DEFAULT now(),
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE,
    CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES public.products (id) ON DELETE CASCADE
);

-- ============================================================================
-- CREATE INDEXES FOR PERFORMANCE
-- ============================================================================

CREATE INDEX idx_users_username ON public.users(username);
CREATE INDEX idx_users_email ON public.users(email);
CREATE INDEX idx_user_profiles_user_id ON public.user_profiles(user_id);
CREATE INDEX idx_user_roles_user_id ON public.user_roles(user_id);
CREATE INDEX idx_user_roles_role_id ON public.user_roles(role_id);
CREATE INDEX idx_products_category_id ON public.products(category_id);
CREATE INDEX idx_orders_user_id ON public.orders(user_id);
CREATE INDEX idx_orders_status ON public.orders(status);
CREATE INDEX idx_order_items_order_id ON public.order_items(order_id);
CREATE INDEX idx_order_items_product_id ON public.order_items(product_id);
CREATE INDEX idx_payments_order_id ON public.payments(order_id);
CREATE INDEX idx_shipments_order_id ON public.shipments(order_id);
CREATE INDEX idx_reviews_user_id ON public.reviews(user_id);
CREATE INDEX idx_reviews_product_id ON public.reviews(product_id);

-- ============================================================================
-- SAMPLE DATA FOR TESTING
-- ============================================================================

-- Insert sample roles
INSERT INTO public.roles (id, role_name, description, is_default) VALUES
    ('550e8400-e29b-41d4-a716-446655440001'::UUID, 'ADMIN', 'Administrator with full access', FALSE),
    ('550e8400-e29b-41d4-a716-446655440002'::UUID, 'USER', 'Regular user', TRUE),
    ('550e8400-e29b-41d4-a716-446655440003'::UUID, 'MODERATOR', 'Moderator with limited access', FALSE);

-- Insert sample users
INSERT INTO public.users (id, username, email, is_active) VALUES
    ('550e8400-e29b-41d4-a716-446655440010'::UUID, 'admin', 'admin@example.com', TRUE),
    ('550e8400-e29b-41d4-a716-446655440011'::UUID, 'john_doe', 'john@example.com', TRUE),
    ('550e8400-e29b-41d4-a716-446655440012'::UUID, 'jane_smith', 'jane@example.com', TRUE),
    ('550e8400-e29b-41d4-a716-446655440013'::UUID, 'bob_wilson', 'bob@example.com', TRUE),
    ('550e8400-e29b-41d4-a716-446655440014'::UUID, 'alice_brown', 'alice@example.com', TRUE);

-- Insert sample user profiles
INSERT INTO public.user_profiles (id, user_id, full_name, birth_date, country, bio) VALUES
    ('550e8400-e29b-41d4-a716-446655440020'::UUID, '550e8400-e29b-41d4-a716-446655440010'::UUID, 'Admin User', '1990-01-15', 'United States', 'System administrator'),
    ('550e8400-e29b-41d4-a716-446655440021'::UUID, '550e8400-e29b-41d4-a716-446655440011'::UUID, 'John Doe', '1992-03-22', 'United States', 'Software developer'),
    ('550e8400-e29b-41d4-a716-446655440022'::UUID, '550e8400-e29b-41d4-a716-446655440012'::UUID, 'Jane Smith', '1994-07-18', 'Canada', 'Product manager'),
    ('550e8400-e29b-41d4-a716-446655440023'::UUID, '550e8400-e29b-41d4-a716-446655440013'::UUID, 'Bob Wilson', '1988-11-30', 'United Kingdom', 'Designer'),
    ('550e8400-e29b-41d4-a716-446655440024'::UUID, '550e8400-e29b-41d4-a716-446655440014'::UUID, 'Alice Brown', '1996-05-12', 'Germany', 'Data analyst');

-- Assign user roles
INSERT INTO public.user_roles (id, user_id, role_id, assigned_by) VALUES
    ('550e8400-e29b-41d4-a716-446655440030'::UUID, '550e8400-e29b-41d4-a716-446655440010'::UUID, '550e8400-e29b-41d4-a716-446655440001'::UUID, NULL),
    ('550e8400-e29b-41d4-a716-446655440031'::UUID, '550e8400-e29b-41d4-a716-446655440011'::UUID, '550e8400-e29b-41d4-a716-446655440002'::UUID, '550e8400-e29b-41d4-a716-446655440010'::UUID),
    ('550e8400-e29b-41d4-a716-446655440032'::UUID, '550e8400-e29b-41d4-a716-446655440012'::UUID, '550e8400-e29b-41d4-a716-446655440002'::UUID, '550e8400-e29b-41d4-a716-446655440010'::UUID),
    ('550e8400-e29b-41d4-a716-446655440033'::UUID, '550e8400-e29b-41d4-a716-446655440013'::UUID, '550e8400-e29b-41d4-a716-446655440002'::UUID, '550e8400-e29b-41d4-a716-446655440010'::UUID),
    ('550e8400-e29b-41d4-a716-446655440034'::UUID, '550e8400-e29b-41d4-a716-446655440014'::UUID, '550e8400-e29b-41d4-a716-446655440002'::UUID, '550e8400-e29b-41d4-a716-446655440010'::UUID);

-- Insert sample categories
INSERT INTO public.categories (id, category_name, description) VALUES
    ('550e8400-e29b-41d4-a716-446655440040'::UUID, 'Electronics', 'Electronic devices and gadgets'),
    ('550e8400-e29b-41d4-a716-446655440041'::UUID, 'Books', 'Books and literature'),
    ('550e8400-e29b-41d4-a716-446655440042'::UUID, 'Clothing', 'Fashion and apparel'),
    ('550e8400-e29b-41d4-a716-446655440043'::UUID, 'Home & Garden', 'Home and garden supplies');

/*
 * *****************************************************************************
 * Copyright (c)  2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 * ****************************************************************************
 */

-- Insert sample products
INSERT INTO public.products (id, category_id, product_name, description, price, stock) VALUES
    ('550e8400-e29b-41d4-a716-446655440050'::UUID, '550e8400-e29b-41d4-a716-446655440040'::UUID, 'Laptop Pro', 'High-performance laptop for professionals', 1299.99, 15),
    ('550e8400-e29b-41d4-a716-446655440051'::UUID, '550e8400-e29b-41d4-a716-446655440040'::UUID, 'Wireless Mouse', 'Ergonomic wireless mouse', 29.99, 150),
    ('550e8400-e29b-41d4-a716-446655440052'::UUID, '550e8400-e29b-41d4-a716-446655440041'::UUID, 'Database Design Book', 'Comprehensive guide to database design', 45.99, 50),
    ('550e8400-e29b-41d4-a716-446655440053'::UUID, '550e8400-e29b-41d4-a716-446655440042'::UUID, 'Cotton T-Shirt', 'Comfortable everyday t-shirt', 19.99, 200),
    ('550e8400-e29b-41d4-a716-446655440054'::UUID, '550e8400-e29b-41d4-a716-446655440043'::UUID, 'Coffee Maker', 'Automatic drip coffee maker', 89.99, 25);

-- Insert sample orders
INSERT INTO public.orders (id, user_id, status, total_amount, notes) VALUES
    ('550e8400-e29b-41d4-a716-446655440060'::UUID, '550e8400-e29b-41d4-a716-446655440011'::UUID, 'COMPLETED', 1329.97, 'Rush delivery requested'),
    ('550e8400-e29b-41d4-a716-446655440061'::UUID, '550e8400-e29b-41d4-a716-446655440012'::UUID, 'PENDING', 65.97, NULL),
    ('550e8400-e29b-41d4-a716-446655440062'::UUID, '550e8400-e29b-41d4-a716-446655440013'::UUID, 'SHIPPED', 109.97, 'Gift wrapping included'),
    ('550e8400-e29b-41d4-a716-446655440063'::UUID, '550e8400-e29b-41d4-a716-446655440014'::UUID, 'COMPLETED', 49.98, NULL);

-- Insert sample order items
INSERT INTO public.order_items (id, order_id, product_id, quantity, unit_price, discount) VALUES
    ('550e8400-e29b-41d4-a716-446655440070'::UUID, '550e8400-e29b-41d4-a716-446655440060'::UUID, '550e8400-e29b-41d4-a716-446655440050'::UUID, 1, 1299.99, 0),
    ('550e8400-e29b-41d4-a716-446655440071'::UUID, '550e8400-e29b-41d4-a716-446655440060'::UUID, '550e8400-e29b-41d4-a716-446655440051'::UUID, 1, 29.99, 0),
    ('550e8400-e29b-41d4-a716-446655440072'::UUID, '550e8400-e29b-41d4-a716-446655440061'::UUID, '550e8400-e29b-41d4-a716-446655440052'::UUID, 1, 45.99, 0),
    ('550e8400-e29b-41d4-a716-446655440073'::UUID, '550e8400-e29b-41d4-a716-446655440061'::UUID, '550e8400-e29b-41d4-a716-446655440053'::UUID, 1, 19.99, 0),
    ('550e8400-e29b-41d4-a716-446655440074'::UUID, '550e8400-e29b-41d4-a716-446655440062'::UUID, '550e8400-e29b-41d4-a716-446655440054'::UUID, 1, 89.99, 5),
    ('550e8400-e29b-41d4-a716-446655440075'::UUID, '550e8400-e29b-41d4-a716-446655440062'::UUID, '550e8400-e29b-41d4-a716-446655440053'::UUID, 1, 19.99, 0),
    ('550e8400-e29b-41d4-a716-446655440076'::UUID, '550e8400-e29b-41d4-a716-446655440063'::UUID, '550e8400-e29b-41d4-a716-446655440053'::UUID, 2, 24.99, 0);

-- Insert sample payments
INSERT INTO public.payments (id, order_id, amount, method, status) VALUES
    ('550e8400-e29b-41d4-a716-446655440080'::UUID, '550e8400-e29b-41d4-a716-446655440060'::UUID, 1329.97, 'CREDIT_CARD', 'COMPLETED'),
    ('550e8400-e29b-41d4-a716-446655440081'::UUID, '550e8400-e29b-41d4-a716-446655440061'::UUID, 65.97, 'DEBIT_CARD', 'PENDING'),
    ('550e8400-e29b-41d4-a716-446655440082'::UUID, '550e8400-e29b-41d4-a716-446655440062'::UUID, 109.97, 'PAYPAL', 'COMPLETED'),
    ('550e8400-e29b-41d4-a716-446655440083'::UUID, '550e8400-e29b-41d4-a716-446655440063'::UUID, 49.98, 'CREDIT_CARD', 'COMPLETED');

-- Insert sample shipments
INSERT INTO public.shipments (id, order_id, shipped_date, estimated_delivery, carrier, tracking_number, status) VALUES
    ('550e8400-e29b-41d4-a716-446655440090'::UUID, '550e8400-e29b-41d4-a716-446655440060'::UUID, NOW() - INTERVAL '5 days', NOW() + INTERVAL '1 day', 'FedEx', 'FDX123456789', 'DELIVERED'),
    ('550e8400-e29b-41d4-a716-446655440091'::UUID, '550e8400-e29b-41d4-a716-446655440062'::UUID, NOW() - INTERVAL '2 days', NOW() + INTERVAL '3 days', 'UPS', 'UPS987654321', 'IN_TRANSIT');

-- Insert sample reviews
INSERT INTO public.reviews (id, user_id, product_id, rating, comment) VALUES
    ('550e8400-e29b-41d4-a716-446655440100'::UUID, '550e8400-e29b-41d4-a716-446655440011'::UUID, '550e8400-e29b-41d4-a716-446655440050'::UUID, 5, 'Excellent laptop! Very satisfied with the performance.'),
    ('550e8400-e29b-41d4-a716-446655440101'::UUID, '550e8400-e29b-41d4-a716-446655440012'::UUID, '550e8400-e29b-41d4-a716-446655440052'::UUID, 4, 'Great book, very informative. Highly recommend.'),
    ('550e8400-e29b-41d4-a716-446655440102'::UUID, '550e8400-e29b-41d4-a716-446655440013'::UUID, '550e8400-e29b-41d4-a716-446655440051'::UUID, 4, 'Good mouse, very comfortable for long work sessions.'),
    ('550e8400-e29b-41d4-a716-446655440103'::UUID, '550e8400-e29b-41d4-a716-446655440014'::UUID, '550e8400-e29b-41d4-a716-446655440054'::UUID, 5, 'Perfect coffee maker! Makes excellent coffee.');
