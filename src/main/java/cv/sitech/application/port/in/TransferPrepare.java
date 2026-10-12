package cv.sitech.application.port.in;

import java.math.BigDecimal;

import cv.sitech.domain.CurrencyEnum;

/** A transfer the payee's SDK received from the hub, with the id of the quote the payee answered earlier. */
public record TransferPrepare(String transferId, String quoteId, String transactionId, BigDecimal amount,
    CurrencyEnum currency) {
}
