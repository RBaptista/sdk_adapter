package cv.sitech.application.port.in;

/** The payer SDK's reports on the bulk transactions of hosted DFSP {@code fspId}. */
public interface BulkTransactionCallbackUseCase {
  void bulkTransactionUpdated(String fspId, BulkTransactionUpdate update);
}
