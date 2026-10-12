package cv.sitech.application.port.in;

import java.util.List;

import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.PartyInfo;
import cv.sitech.domain.transfer.Transfer;

/**
 * Requests from a hosted DFSP's SDK scheme adapter, acting as payee. The SDK waits for each answer
 * and turns it into the FSPIOP callback; a {@code BusinessException} becomes an FSPIOP error.
 */
public interface PayeeUseCase {
  /** the hosted party, or a {@code BusinessException} 3204 */
  PartyInfo lookupParty(String fspId, IdTypeEnum idType, String idValue);

  /** prices the transfer and stores it as an INBOUND row in state QUOTED */
  Transfer quote(String fspId, QuoteRequest request);

  /** books the transfer against its quote; the result's state (COMMITTED or RESERVED) is the answer to the hub */
  Transfer prepareTransfer(String fspId, TransferPrepare prepare);

  /**
   * books one transfer of a bulk transfer against its quote and commits it at once, whatever
   * {@code simulator.payee.transfer-state} says: the SDK relays no final notification for bulk transfers
   */
  Transfer prepareBulkTransfer(String fspId, TransferPrepare prepare);

  /** the hub's final state after the payee answered RESERVED: COMMITTED or ABORTED */
  void transferNotified(String fspId, String transferId, String transferState);

  List<Transfer> findAll(String fspId);
}
