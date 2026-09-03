-- TODO: Pegar aquí los CREATE TABLE correspondientes al esquema 'analytics'.
-- REGLA: 0 FK hacia otros esquemas. Relaciones inter-dominio solo por ID.
-- ============================================================
-- REPORTING + ANALYTICS / reporting-analytics-service
-- Read models are eventually consistent and never authoritative for stock.
-- ============================================================
CREATE TABLE analytics.product_projection (
    product_id uuid PRIMARY KEY,
    sku varchar(80) NOT NULL,
    name varchar(200) NOT NULL,
    category_code varchar(50),
    category_name varchar(120),
    product_type varchar(40) NOT NULL,
    base_unit_code varchar(20) NOT NULL,
    storage_unit_code varchar(20) NOT NULL,
    min_stock numeric(18,6) NOT NULL DEFAULT 0,
    active boolean NOT NULL,
    source_version bigint NOT NULL DEFAULT 0,
    source_event_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_product_projection_category ON analytics.product_projection(category_code, active);

CREATE TABLE analytics.inventory_projection (
    stock_balance_id uuid PRIMARY KEY,
    product_id uuid NOT NULL,
    location_id uuid NOT NULL,
    lot_id uuid,
    asset_id uuid,
    quantity numeric(18,6) NOT NULL,
    avg_cost_pen numeric(18,6) NOT NULL,
    min_stock numeric(18,6) NOT NULL DEFAULT 0,
    source_event_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_inventory_projection_product ON analytics.inventory_projection(product_id);
CREATE INDEX idx_inventory_projection_location ON analytics.inventory_projection(location_id);
CREATE INDEX idx_inventory_projection_low_stock ON analytics.inventory_projection(product_id) WHERE quantity <= min_stock;

CREATE TABLE analytics.movement_projection (
    movement_id uuid PRIMARY KEY,
    movement_code varchar(80) NOT NULL,
    movement_type varchar(30) NOT NULL,
    status varchar(40) NOT NULL,
    registered_by_user_id uuid,
    requested_by_user_id uuid,
    cost_center_code varchar(80),
    cost_center_name_snapshot varchar(160),
    supplier_id uuid,
    supplier_name_snapshot varchar(200),
    supplier_tax_id_snapshot varchar(20),
    total_base_cost_pen numeric(18,6) NOT NULL DEFAULT 0,
    confirmed_at timestamptz,
    source_event_id uuid,
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_movement_projection_confirmed ON analytics.movement_projection(confirmed_at DESC);
CREATE INDEX idx_movement_projection_cost_center ON analytics.movement_projection(cost_center_code, confirmed_at DESC) WHERE cost_center_code IS NOT NULL;
CREATE INDEX idx_movement_projection_supplier ON analytics.movement_projection(supplier_id, confirmed_at DESC) WHERE supplier_id IS NOT NULL;

CREATE TABLE analytics.kpi_snapshot (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    metric_code varchar(80) NOT NULL,
    dimension_key varchar(160) NOT NULL DEFAULT 'GLOBAL',
    metric_value numeric(20,6) NOT NULL,
    measured_at timestamptz NOT NULL,
    source_event_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (metric_code, dimension_key, measured_at)
);
CREATE INDEX idx_kpi_metric_time ON analytics.kpi_snapshot(metric_code, measured_at DESC);

CREATE TABLE analytics.report_job (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    report_type varchar(80) NOT NULL,
    requested_by_user_id uuid NOT NULL,       -- external IAM reference
    format varchar(10) NOT NULL CHECK (format IN ('PDF','XLSX','CSV')),
    filters jsonb NOT NULL DEFAULT '{}'::jsonb,
    persist_result boolean NOT NULL DEFAULT false,
    status varchar(30) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','RUNNING','COMPLETED','FAILED')),
    object_key varchar(500),
    sha256 char(64),
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz
);
CREATE INDEX idx_report_job_user_created ON analytics.report_job(requested_by_user_id, created_at DESC);

CREATE TABLE analytics.processed_event (
    event_id uuid NOT NULL,
    consumer varchar(120) NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

CREATE TABLE analytics.export_checkpoint (
    consumer varchar(120) PRIMARY KEY,
    last_event_id uuid,
    last_occurred_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
