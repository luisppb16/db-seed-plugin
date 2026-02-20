/*
 * Copyright (c) 2026 Luis Paolo Pepe Barra (@LuisPPB16).
 * All rights reserved.
 */

CREATE TABLE public.users
(
    id         UUID PRIMARY KEY,
    username   VARCHAR(50) UNIQUE,
    email      VARCHAR(100) UNIQUE,
    created_at TIMESTAMP DEFAULT now(),
    is_active  BOOLEAN   DEFAULT TRUE
);

CREATE TABLE public.user_profiles
(
    id         UUID PRIMARY KEY,
    user_id    UUID UNIQUE,
    full_name  VARCHAR(100),
    birth_date DATE,
    country    VARCHAR(50),
    bio        TEXT,
    CONSTRAINT fk_user_profile FOREIGN KEY (user_id) REFERENCES public.users (id)
);

CREATE TABLE public.roles
(
    id          UUID PRIMARY KEY,
    role_name   VARCHAR(50) UNIQUE,
    description TEXT,
    created_at  TIMESTAMP DEFAULT now(),
    is_default  BOOLEAN   DEFAULT FALSE
);

CREATE TABLE public.user_roles
(
    id          UUID PRIMARY KEY,
    user_id     UUID,
    role_id     UUID,
    assigned_at TIMESTAMP DEFAULT now(),
    assigned_by UUID,
    notes       TEXT,
    CONSTRAINT uq_user_roles UNIQUE (user_id, role_id),
    CONSTRAINT fk_user FOREIGN KEY (user_id) REFERENCES public.users (id),
    CONSTRAINT fk_role FOREIGN KEY (role_id) REFERENCES public.roles (id),
    CONSTRAINT fk_assigned_by FOREIGN KEY (assigned_by) REFERENCES public.users (id)
);

CREATE TABLE public.products
(
    id           UUID PRIMARY KEY,
    product_name VARCHAR(100),
    description  TEXT,
    price        NUMERIC(10, 2),
    stock        INT DEFAULT 0
);

CREATE TABLE public.orders
(
    id           UUID PRIMARY KEY,
    user_id      UUID,
    order_date   TIMESTAMP DEFAULT now(),
    status       VARCHAR(20),
    total_amount NUMERIC(10, 2),
    CONSTRAINT fk_order_user FOREIGN KEY (user_id) REFERENCES public.users (id)
);

CREATE TABLE public.order_items
(
    id         UUID PRIMARY KEY,
    order_id   UUID,
    product_id UUID,
    quantity   INT,
    unit_price NUMERIC(10, 2),
    discount   NUMERIC(5, 2) DEFAULT 0,
    CONSTRAINT uq_order_items UNIQUE (order_id, product_id),
    CONSTRAINT fk_order FOREIGN KEY (order_id) REFERENCES public.orders (id),
    CONSTRAINT fk_product FOREIGN KEY (product_id) REFERENCES public.products (id)
);

CREATE TABLE public.payments
(
    id           UUID PRIMARY KEY,
    order_id     UUID,
    payment_date TIMESTAMP DEFAULT now(),
    amount       NUMERIC(10, 2),
    method       VARCHAR(20),
    CONSTRAINT fk_payment_order FOREIGN KEY (order_id) REFERENCES public.orders (id)
);

CREATE TABLE public.shipments
(
    id              UUID PRIMARY KEY,
    order_id        UUID,
    shipped_date    TIMESTAMP,
    carrier         VARCHAR(50),
    carrier_tags    TEXT[],
    tracking_number VARCHAR(100),
    CONSTRAINT fk_shipment_order FOREIGN KEY (order_id) REFERENCES public.orders (id)
);

CREATE TABLE public.reviews
(
    id         UUID PRIMARY KEY,
    user_id    UUID,
    product_id UUID,
    rating     INT CHECK (rating BETWEEN 1 AND 5),
    comment    TEXT,
    CONSTRAINT fk_review_user FOREIGN KEY (user_id) REFERENCES public.users (id),
    CONSTRAINT fk_review_product FOREIGN KEY (product_id) REFERENCES public.products (id)
);
