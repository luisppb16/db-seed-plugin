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

CREATE TABLE public.articles
(
    id         UUID PRIMARY KEY,
    title      VARCHAR(200),
    content    TEXT,
    tags       TEXT[],
    keywords   TEXT[],
    categories TEXT[],
    created_at TIMESTAMP DEFAULT now()
);

-- Test fixtures for hierarchy generation and mutual circular references.
CREATE TABLE public.military_ranks
(
    id             UUID PRIMARY KEY,
    rank_name      VARCHAR(100) NOT NULL UNIQUE,
    parent_rank_id UUID,
    level_hint     INT          DEFAULT 0 CHECK (level_hint >= 0),
    CONSTRAINT chk_military_ranks_no_self_loop CHECK (parent_rank_id IS NULL OR parent_rank_id <> id),
    CONSTRAINT fk_military_ranks_parent FOREIGN KEY (parent_rank_id)
        REFERENCES public.military_ranks (id)
        DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE public.department_nodes
(
    id          UUID PRIMARY KEY,
    node_name   VARCHAR(120) NOT NULL,
    parent_id   UUID,
    depth_limit INT          NOT NULL CHECK (depth_limit >= 1 AND depth_limit <= 12),
    CONSTRAINT chk_department_nodes_no_self_loop CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT fk_department_nodes_parent FOREIGN KEY (parent_id)
        REFERENCES public.department_nodes (id)
        DEFERRABLE INITIALLY DEFERRED
);

-- Mutual cycle of length 2.
CREATE TABLE public.cycle2_a
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    b_ref_id  UUID NOT NULL UNIQUE
);

CREATE TABLE public.cycle2_b
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    a_ref_id  UUID NOT NULL UNIQUE
);

ALTER TABLE public.cycle2_a
    ADD CONSTRAINT fk_cycle2_a_to_b FOREIGN KEY (b_ref_id)
        REFERENCES public.cycle2_b (id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cycle2_b
    ADD CONSTRAINT fk_cycle2_b_to_a FOREIGN KEY (a_ref_id)
        REFERENCES public.cycle2_a (id)
        DEFERRABLE INITIALLY DEFERRED;

-- Mutual cycle of length 3.
CREATE TABLE public.cycle3_a
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    b_ref_id  UUID NOT NULL UNIQUE
);

CREATE TABLE public.cycle3_b
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    c_ref_id  UUID NOT NULL UNIQUE
);

CREATE TABLE public.cycle3_c
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    a_ref_id  UUID NOT NULL UNIQUE
);

ALTER TABLE public.cycle3_a
    ADD CONSTRAINT fk_cycle3_a_to_b FOREIGN KEY (b_ref_id)
        REFERENCES public.cycle3_b (id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cycle3_b
    ADD CONSTRAINT fk_cycle3_b_to_c FOREIGN KEY (c_ref_id)
        REFERENCES public.cycle3_c (id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cycle3_c
    ADD CONSTRAINT fk_cycle3_c_to_a FOREIGN KEY (a_ref_id)
        REFERENCES public.cycle3_a (id)
        DEFERRABLE INITIALLY DEFERRED;

-- Mutual cycle of length 4.
CREATE TABLE public.cycle4_a
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    b_ref_id  UUID NOT NULL UNIQUE
);

CREATE TABLE public.cycle4_b
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    c_ref_id  UUID NOT NULL UNIQUE
);

CREATE TABLE public.cycle4_c
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    d_ref_id  UUID NOT NULL UNIQUE
);

CREATE TABLE public.cycle4_d
(
    id        UUID PRIMARY KEY,
    name      VARCHAR(80),
    a_ref_id  UUID NOT NULL UNIQUE
);

ALTER TABLE public.cycle4_a
    ADD CONSTRAINT fk_cycle4_a_to_b FOREIGN KEY (b_ref_id)
        REFERENCES public.cycle4_b (id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cycle4_b
    ADD CONSTRAINT fk_cycle4_b_to_c FOREIGN KEY (c_ref_id)
        REFERENCES public.cycle4_c (id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cycle4_c
    ADD CONSTRAINT fk_cycle4_c_to_d FOREIGN KEY (d_ref_id)
        REFERENCES public.cycle4_d (id)
        DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE public.cycle4_d
    ADD CONSTRAINT fk_cycle4_d_to_a FOREIGN KEY (a_ref_id)
        REFERENCES public.cycle4_a (id)
        DEFERRABLE INITIALLY DEFERRED;

