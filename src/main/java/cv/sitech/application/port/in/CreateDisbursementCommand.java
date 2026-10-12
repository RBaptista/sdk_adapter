package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.util.List;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;

/** A G2P batch paid by a party of the logged-in DFSP; {@code currency} is optional, as for a transfer. */
public record CreateDisbursementCommand(IdTypeEnum payerIdType, String payerIdValue, CurrencyEnum currency,
    String reference, String note, List<Item> items) {

  public record Item(IdTypeEnum payeeIdType, String payeeIdValue, BigDecimal amount, String note) {
  }
}
