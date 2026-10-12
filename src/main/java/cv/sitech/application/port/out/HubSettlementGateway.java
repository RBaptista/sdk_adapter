package cv.sitech.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Read-only view of the hub's positions and settlements (central-ledger / central-settlement). */
public interface HubSettlementGateway {

  record HubPosition(String currency, BigDecimal value) {
  }

  record HubWindow(String id, Instant openedAt, Instant closedAt) {
  }

  /** {@code amount} > 0 means the participant owes the hub */
  record HubNet(String fspId, String currency, BigDecimal amount) {
  }

  record HubSettlement(String id, String state, List<HubWindow> windows, List<HubNet> nets) {
  }

  List<HubPosition> positions(String fspId);

  List<HubSettlement> settlementsSince(Instant from);
}
