package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.util.List;

import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;

public interface DfspUseCase {
  /**
   * creates the DFSP with its POSITION and SETTLEMENT ledger accounts. {@code sdkOutboundUrl} is
   * the outbound API of the DFSP's SDK scheme adapter; a null {@code sdkInboundUrl} uses
   * {@code simulator.sdk.inbound-url-template}.
   */
  Dfsp create(String fspId, String displayName, CurrencyEnum currency, String sdkOutboundUrl, String sdkInboundUrl,
      BigDecimal netDebitCap, BigDecimal initialPosition, BigDecimal initialFunds);

  Dfsp findById(String fspId);

  List<Dfsp> findAll();

  /** null arguments keep the current value; a changed inbound URL takes effect at the next onboarding */
  Dfsp update(String fspId, String displayName, String sdkOutboundUrl, String sdkInboundUrl, BigDecimal netDebitCap);

  /** registers the DFSP in the hub (participant, endpoints to its SDK, limits, initial funds) */
  void onboard(String fspId);

}
