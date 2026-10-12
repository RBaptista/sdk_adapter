package cv.sitech.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.TransferEntity;
import cv.sitech.adapter.out.persistence.mapper.TransferMapper;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferDirection;
import cv.sitech.domain.transfer.TransferState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class TransferPersistenceAdapter extends PanacheAdapter<Transfer, TransferEntity, Long>
    implements TransferRepository {

  private static final List<String> FINAL_STATES = List.of(TransferState.COMMITTED.name(),
      TransferState.ABORTED.name(), TransferState.EXPIRED.name());

  protected TransferPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public TransferPersistenceAdapter(TransferPanacheRepository panache, TransferMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public Optional<Transfer> findByTransferId(TransferDirection direction, String transferId) {
    return first("direction = ?1 and transferId = ?2", direction.name(), transferId);
  }

  @Override
  public Optional<Transfer> findByQuoteId(TransferDirection direction, String quoteId) {
    return first("direction = ?1 and quoteId = ?2", direction.name(), quoteId);
  }

  @Override
  public Optional<Transfer> findOutboundByHomeTransactionId(String homeTransactionId) {
    return first("direction = ?1 and homeTransactionId = ?2", TransferDirection.OUTBOUND.name(), homeTransactionId);
  }

  @Override
  public List<Transfer> findOutboundByPayer(String payerFspId) {
    return list("direction = ?1 and payerFspId = ?2 order by createdAt desc", TransferDirection.OUTBOUND.name(),
        payerFspId);
  }

  @Override
  public List<Transfer> findInboundByPayee(String payeeFspId) {
    return list("direction = ?1 and payeeFspId = ?2 order by createdAt desc", TransferDirection.INBOUND.name(),
        payeeFspId);
  }

  @Override
  public List<Transfer> findExpired(Instant now) {
    return list("state not in ?1 and expiration < ?2", FINAL_STATES, now);
  }

  @Override
  public List<Transfer> findCommitted(String fspId, Instant from, Instant to) {
    return list("state = ?1 and completedAt >= ?2 and completedAt < ?3"
        + " and ((direction = ?4 and payerFspId = ?5) or (direction = ?6 and payeeFspId = ?5))",
        TransferState.COMMITTED.name(), from, to, TransferDirection.OUTBOUND.name(), fspId,
        TransferDirection.INBOUND.name());
  }

}
