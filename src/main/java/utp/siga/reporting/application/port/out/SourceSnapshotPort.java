package utp.siga.reporting.application.port.out;

import java.util.List;
import utp.siga.reporting.domain.model.InventoryProjection;
import utp.siga.reporting.domain.model.ProductProjection;

public interface SourceSnapshotPort {
    Snapshot load(String bearerToken);

    record Snapshot(List<ProductProjection> products, List<InventoryProjection> balances) { }
}
