package cv.sitech.application.port.in;

import java.math.BigDecimal;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;

/**
 * A P2P SEND transfer initiated by a customer of the logged-in (payer) DFSP. {@code homeTransactionId}
 * is optional: when given, starting the same transfer twice returns the first one instead of paying twice.
 */
public record StartTransferCommand(IdTypeEnum payerIdType, String payerIdValue, IdTypeEnum payeeIdType,
    String payeeIdValue, BigDecimal amount, CurrencyEnum currency, String note, boolean autoAccept,
    String homeTransactionId) {

  public StartTransferCommand(IdTypeEnum payerIdType, String payerIdValue, IdTypeEnum payeeIdType,
      String payeeIdValue, BigDecimal amount, CurrencyEnum currency, String note, boolean autoAccept) {
    this(payerIdType, payerIdValue, payeeIdType, payeeIdValue, amount, currency, note, autoAccept, null);
  }
}
