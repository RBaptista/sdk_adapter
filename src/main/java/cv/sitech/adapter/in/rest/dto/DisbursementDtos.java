package cv.sitech.adapter.in.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import cv.sitech.adapter.in.rest.dto.TransferDtos.PartyId;
import cv.sitech.application.port.in.CreateDisbursementCommand;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;

public final class DisbursementDtos {

  private DisbursementDtos() {
  }

  public record ItemRequest(PartyId to, BigDecimal amount, String note) {
  }

  /** a G2P batch from a party of the logged-in DFSP to parties known to the hub */
  public record DisbursementRequest(PartyId from, CurrencyEnum currency, String reference, String note,
      List<ItemRequest> items) {
    public CreateDisbursementCommand toCommand() {
      if (from == null) {
        throw new IllegalArgumentException("from is required");
      }
      List<CreateDisbursementCommand.Item> lines = items == null ? List.of() : items.stream()
          .map(i -> i == null || i.to() == null ? null
              : new CreateDisbursementCommand.Item(i.to().idType(), i.to().idValue(), i.amount(), i.note()))
          .toList();
      return new CreateDisbursementCommand(from.idType(), from.idValue(), currency, reference, note, lines);
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DisbursementView(String disbursementId, String state, String mode, String payerFspId, PartyId payer,
      CurrencyEnum currency, String reference, String note, int itemCount, BigDecimal totalAmount,
      int committedCount, BigDecimal committedAmount, int failedCount, Instant createdAt, Instant approvedAt,
      Instant completedAt) {
    public static DisbursementView from(Disbursement d) {
      return new DisbursementView(d.getDisbursementId(), d.getState().name(), d.getMode().name(), d.getPayerFspId(),
          new PartyId(d.getPayerIdType(), d.getPayerIdValue()), d.getCurrency(), d.getReference(), d.getNote(),
          d.getItemCount(), d.getTotalAmount(), d.getCommittedCount(), d.getCommittedAmount(), d.getFailedCount(),
          d.getCreatedAt(), d.getApprovedAt(), d.getCompletedAt());
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ItemView(int lineNo, String state, PartyId payee, String payeeFspId, String payeeName,
      BigDecimal amount, String note, String transferId, String errorCode, String errorDescription,
      Instant updatedAt) {
    public static ItemView from(DisbursementItem i) {
      return new ItemView(i.getLineNo(), i.getState().name(), new PartyId(i.getPayeeIdType(), i.getPayeeIdValue()),
          i.getPayeeFspId(), i.getPayeeName(), i.getAmount(), i.getNote(), i.getTransferId(), i.getErrorCode(),
          i.getErrorDescription(), i.getUpdatedAt());
    }
  }

}
