package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import cv.sitech.domain.transfer.FspiopError;
import cv.sitech.domain.transfer.PartyInfo;

/**
 * What the payer's SDK reports about a bulk transaction ({@code PUT /bulkTransactions/{id}} to the backend):
 * the parties to accept, then the quotes to accept, then each transfer's outcome.
 */
public record BulkTransactionUpdate(String bulkTransactionId, String currentState, FspiopError error,
    List<Item> items) {

  public static final String WAITING_FOR_PARTY_ACCEPTANCE = "WAITING_FOR_PARTY_ACCEPTANCE";
  public static final String WAITING_FOR_QUOTE_ACCEPTANCE = "WAITING_FOR_QUOTE_ACCEPTANCE";

  /** one individual transfer, matched by the {@code homeTransactionId} this app sent */
  public record Item(String homeTransactionId, String transferId, PartyInfo payee, Quote quote,
      String transferState, String fulfilment, Instant completedAt, FspiopError error) {
  }

  public record Quote(String quoteId, BigDecimal transferAmount, BigDecimal payeeReceiveAmount,
      BigDecimal payeeFee, String ilpPacket, String condition, Instant expiration) {
  }
}
