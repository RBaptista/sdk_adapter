package cv.sitech.application.port.in;

import java.math.BigDecimal;
import java.util.List;

import cv.sitech.domain.Account;
import cv.sitech.domain.ledger.JournalEntry;

/** All operations are scoped to the DFSP {@code fspId}. */
public interface AccountUseCase {
  /** opens a CUSTOMER account in the DFSP's currency */
  Account open(String fspId, Long customerId);

  Account findById(String fspId, Long id);

  List<Account> findAll(String fspId);

  /** cash-in at the DFSP: credits the customer against the DFSP's SETTLEMENT account */
  Account deposit(String fspId, Long accountId, BigDecimal amount);

  List<JournalEntry> statement(String fspId, Long accountId);

}
