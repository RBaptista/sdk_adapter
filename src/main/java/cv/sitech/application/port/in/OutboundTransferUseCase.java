package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;

/** User-facing payer API: discovery, quote and transfer driven by the logged-in DFSP. */
public interface OutboundTransferUseCase {
  /** discovery only; blocks until the hub answers or the callback timeout elapses */
  PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue);

  /** starts discovery; with {@code autoAccept} the transfer runs to completion without further calls */
  Transfer start(String fspId, StartTransferCommand command);

  Transfer acceptParty(String fspId, String transferId, boolean accept);

  Transfer acceptQuote(String fspId, String transferId, boolean accept);

  Transfer findById(String fspId, String transferId);

  List<Transfer> findAll(String fspId);
}
