package cv.sitech.adapter.in.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.settlement.SettlementReconciliation;
import cv.sitech.domain.transfer.Transfer;

public final class TransferDtos {

  private TransferDtos() {
  }

  public record PartyId(IdTypeEnum idType, String idValue) {
  }

  /** a SEND transfer from a party of the logged-in DFSP to any party known to the hub */
  public record TransferRequest(PartyId from, PartyId to, BigDecimal amount, CurrencyEnum currency, String note,
      boolean autoAccept) {
    public StartTransferCommand toCommand() {
      if (from == null || to == null) {
        throw new IllegalArgumentException("from and to are required");
      }
      return new StartTransferCommand(from.idType(), from.idValue(), to.idType(), to.idValue(), amount, currency,
          note, autoAccept);
    }
  }

  public record Acceptance(boolean accept) {
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TransferView(String transferId, String direction, String state, String payerFspId, PartyId payer,
      String payeeFspId, PartyId payee, String payeeName, BigDecimal amount, CurrencyEnum currency,
      BigDecimal transferAmount, BigDecimal payeeReceiveAmount, BigDecimal payeeFee, String quoteId,
      Instant expiration, String errorCode, String errorDescription, String note, Instant createdAt,
      Instant completedAt) {
    public static TransferView from(Transfer t) {
      return new TransferView(t.getTransferId(), t.getDirection().name(), t.getState().name(), t.getPayerFspId(),
          new PartyId(t.getPayerIdType(), t.getPayerIdValue()), t.getPayeeFspId(),
          new PartyId(t.getPayeeIdType(), t.getPayeeIdValue()), t.getPayeeName(), t.getAmount(), t.getCurrency(),
          t.getTransferAmount(), t.getPayeeReceiveAmount(), t.getPayeeFee(), t.getQuoteId(), t.getExpiration(),
          t.getErrorCode(), t.getErrorDescription(), t.getNote(), t.getCreatedAt(), t.getCompletedAt());
    }
  }

  public record ReconciliationView(Long id, String kind, String hubReference, String fspId, String currency,
      BigDecimal hubAmount, BigDecimal internalAmount, String status, String hubState, boolean postedToLedger,
      Instant reconciledAt) {
    public static ReconciliationView from(SettlementReconciliation r) {
      return new ReconciliationView(r.getId(), r.getKind().name(), r.getHubReference(), r.getFspId(),
          r.getCurrency(), r.getHubAmount(), r.getInternalAmount(), r.getStatus().name(), r.getHubState(),
          r.isPostedToLedger(), r.getReconciledAt());
    }
  }

}
