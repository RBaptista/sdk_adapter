package cv.sitech.adapter.in.rest.dto;

import java.math.BigDecimal;

import cv.sitech.domain.Account;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;

public final class DfspDtos {

  private DfspDtos() {
  }

  /** {@code sdkOutboundUrl}: the DFSP's SDK outbound API; {@code sdkInboundUrl}: its inbound API as the hub sees it */
  public record CreateDfsp(String fspId, String displayName, CurrencyEnum currency, String sdkOutboundUrl,
      String sdkInboundUrl, BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds) {
  }

  public record UpdateDfsp(String displayName, String sdkOutboundUrl, String sdkInboundUrl, BigDecimal netDebitCap) {
  }

  public record DfspView(String fspId, String displayName, CurrencyEnum currency, String sdkOutboundUrl,
      String sdkInboundUrl, BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds) {
    public static DfspView from(Dfsp d) {
      return new DfspView(d.getFspId(), d.getDisplayName(), d.getCurrency(), d.getSdkOutboundUrl(),
          d.getSdkInboundUrl(), d.getNetDebitCap(), d.getInitialPosition(), d.getInitialFunds());
    }
  }

  /** the logged-in DFSP with its ledger position */
  public record DfspStatus(DfspView dfsp, AccountView position, AccountView settlement) {
  }

  public record AccountView(Long id, String type, Long customerId, BigDecimal balance, BigDecimal reservedBalance,
      BigDecimal pendingCredit, BigDecimal availableBalance, CurrencyEnum currency) {
    public static AccountView from(Account a) {
      return new AccountView(a.getId(), a.getType().name(), a.getCustomer() != null ? a.getCustomer().getId() : null,
          a.getBalance(), a.getReservedBalance(), a.getPendingCredit(), a.availableBalance(),
          a.getDfsp().getCurrency());
    }
  }

}
