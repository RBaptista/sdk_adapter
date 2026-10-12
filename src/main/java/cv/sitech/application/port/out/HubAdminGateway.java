package cv.sitech.application.port.out;

import java.math.BigDecimal;

import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;

/**
 * Hub administration the SDK doesn't offer: participant onboarding (central-ledger) and removing a
 * party from the ALS. Registering a party goes through {@link SdkOutboundGateway#registerParty}.
 */
public interface HubAdminGateway {

  /** creates the participant, its callback endpoints, NDC limit and initial position; idempotent */
  void onboard(Dfsp dfsp);

  void recordFundsIn(String fspId, BigDecimal amount, String currency);

  void deregisterParty(String fspId, IdTypeEnum idType, String idValue);
}
