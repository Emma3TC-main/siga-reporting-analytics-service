CREATE TABLE analytics.product_projection (
    product_id uuid PRIMARY KEY,
    sku varchar(80) NOT NULL,
    name varchar(200) NOT NULL,
    category_code varchar(50),
    category_name varchar(120),
    product_type varchar(40) NOT NULL,
    base_unit_code varchar(20) NOT NULL,
    storage_unit_code varchar(20) NOT NULL,
    min_stock numeric(18,6) NOT NULL CHECK (min_stock >= 0),
    active boolean NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 0),
    source_event_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_product_projection_category
    ON analytics.product_projection(category_code, active);
CREATE INDEX idx_product_projection_active_sku
    ON analytics.product_projection(active, sku, product_id);
CREATE INDEX idx_product_projection_updated_at
    ON analytics.product_projection(updated_at DESC);

CREATE TABLE analytics.inventory_projection (
    stock_balance_id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    location_id uuid NOT NULL,
    lot_id uuid,
    asset_id uuid,
    quantity numeric(18,6) NOT NULL CHECK (quantity >= 0),
    avg_cost_pen numeric(18,6) NOT NULL CHECK (avg_cost_pen >= 0),
    min_stock numeric(18,6) NOT NULL CHECK (min_stock >= 0),
    source_event_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_inventory_projection_product
    ON analytics.inventory_projection(product_id);
CREATE INDEX idx_inventory_projection_location
    ON analytics.inventory_projection(location_id);
CREATE INDEX idx_inventory_projection_value
    ON analytics.inventory_projection((quantity * avg_cost_pen) DESC, stock_balance_id);
CREATE INDEX idx_inventory_projection_updated_at
    ON analytics.inventory_projection(updated_at DESC);

CREATE TABLE analytics.movement_projection (
    movement_id uuid PRIMARY KEY,
    movement_code varchar(80) NOT NULL,
    movement_type varchar(30) NOT NULL CHECK (movement_type IN ('ENTRY','EXIT','TRANSFER','ADJUST_POSITIVE','ADJUST_NEGATIVE')),
    status varchar(40) NOT NULL CHECK (status = 'CONFIRMED'),
    registered_by_user_id uuid NOT NULL,
    confirmed_by_user_id uuid NOT NULL,
    requested_by_user_id uuid,
    authorized_by_user_id uuid,
    cost_center_code varchar(80),
    cost_center_name_snapshot varchar(160),
    supplier_id uuid,
    supplier_name_snapshot varchar(200),
    supplier_tax_id_snapshot varchar(20),
    reference_doc_type varchar(80),
    reference_doc_number varchar(160),
    reason text,
    sensitive boolean NOT NULL,
    sensitivity_reason varchar(200),
    total_base_cost_pen numeric(18,6) NOT NULL CHECK (total_base_cost_pen >= 0),
    confirmed_at timestamptz NOT NULL,
    source_event_id uuid NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_movement_projection_confirmed
    ON analytics.movement_projection(confirmed_at DESC, movement_id DESC);
CREATE INDEX idx_movement_projection_type_confirmed
    ON analytics.movement_projection(movement_type, confirmed_at DESC, movement_id DESC);
CREATE INDEX idx_movement_projection_cost_center
    ON analytics.movement_projection(cost_center_code, confirmed_at DESC, movement_id DESC)
    WHERE cost_center_code IS NOT NULL;
CREATE INDEX idx_movement_projection_updated_at
    ON analytics.movement_projection(updated_at DESC);

CREATE TABLE analytics.processed_event (
    event_id uuid NOT NULL,
    consumer varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE INDEX idx_processed_event_processed_at
    ON analytics.processed_event(processed_at DESC);
