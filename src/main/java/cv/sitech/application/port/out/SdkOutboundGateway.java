package cv.sitech.application.port.out;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;

/**
 * The outbound API of the Mojaloop SDK scheme adapter of a hosted DFSP. Calls are synchronous: the
 * SDK talks FSPIOP to the hub and answers once the hub's callback arrives. A failed call throws a
 * {@code BusinessException} carrying the FSPIOP error code.
 */
public interface SdkOutboundGateway {

  /** SDK view of an outbound transfer after a step; {@code currentState} is the SDK's state machine */
  record SdkTransfer(String transferId, String currentState, PartyInfo payee, String quoteId,
      BigDecimal transferAmount, BigDecimal payeeReceiveAmount, BigDecimal payeeFee, Instant expiration,
      String ilpPacket, String condition, String fulfilment, String transferState, Instant completedAt,
      FspiopError error) {

    public boolean committed() {
      return "COMMITTED".equals(transferState);
    }

    public boolean failed() {
      return error != null || "ABORTED".equals(transferState) || "ABORTED".equals(currentState)
          || "ERROR_OCCURRED".equals(currentState);
    }
  }

  PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue);

  /** starts discovery; the SDK stops at WAITING_FOR_PARTY_ACCEPTANCE */
  SdkTransfer startTransfer(String fspId, Transfer transfer);

  /** accepting asks for a quote; the SDK stops at WAITING_FOR_QUOTE_ACCEPTANCE */
  SdkTransfer acceptParty(String fspId, String transferId, boolean accept);

  /** accepting sends the transfer to the hub and waits for its fulfil */
  SdkTransfer acceptQuote(String fspId, String transferId, boolean accept);

  /** asks the hub for the transfer's current state */
  SdkTransfer getTransfer(String fspId, String transferId);

  /** one payment of a bulk transaction, identified by this app's {@code homeTransactionId} */
  record BulkItem(String homeTransactionId, IdTypeEnum payeeIdType, String payeeIdValue, BigDecimal amount,
      CurrencyEnum currency, String note) {
  }

  /**
   * Starts a bulk transaction ({@code POST /bulkTransactions}). It is asynchronous: the SDK looks every payee
   * up and reports to the backend ({@code PUT /bulkTransactions/{id}}) for each acceptance and at the end.
   */
  void startBulkTransaction(String fspId, String bulkTransactionId, IdTypeEnum payerIdType, String payerIdValue,
      List<BulkItem> items, Instant expiration);

  /** accepts or rejects each payee by the SDK's transfer id; the SDK then asks for bulk quotes */
  void acceptBulkParties(String fspId, String bulkTransactionId, Map<String, Boolean> acceptByTransferId);

  /** accepts or rejects each quote by the SDK's transfer id; the SDK then sends the bulk transfers */
  void acceptBulkQuotes(String fspId, String bulkTransactionId, Map<String, Boolean> acceptByTransferId);

  /** registers the identifier in the hub's ALS as hosted by {@code fspId} */
  void registerParty(String fspId, IdTypeEnum idType, String idValue, String currency);
}
