-- TODO: Pegar aquí los INSERT iniciales del esquema 'analytics'.
-- Reporting read models (normally maintained by RabbitMQ consumers).
INSERT INTO analytics.product_projection(product_id,sku,name,category_code,category_name,product_type,base_unit_code,storage_unit_code,min_stock,active,source_version)
SELECT p.id,p.sku,p.name,c.code,c.name,p.product_type,ub.code,us.code,p.min_stock,p.active,p.version
FROM catalog.product p JOIN catalog.category c ON c.id=p.category_id
JOIN catalog.unit_measure ub ON ub.id=p.base_unit_id
JOIN catalog.unit_measure us ON us.id=p.storage_unit_id;

INSERT INTO analytics.inventory_projection(stock_balance_id,product_id,location_id,lot_id,asset_id,quantity,avg_cost_pen,min_stock)
SELECT s.id,s.product_id,s.location_id,s.lot_id,s.asset_id,s.quantity,s.avg_cost_pen,p.min_stock
FROM inventory.stock_balance s JOIN inventory.product_ref p ON p.product_id=s.product_id;
