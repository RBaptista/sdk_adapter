package cv.sitech.application.port.in;

import java.math.BigDecimal;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.transfer.PartyInfo;

/** An incoming FSPIOP POST /quotes, as seen by the payee. {@code amountType} is SEND or RECEIVE. */
public record QuoteRequest(String quoteId, String transactionId, PartyInfo payer, PartyInfo payee, String amountType,
    BigDecimal amount, CurrencyEnum currency, String note) {
}
