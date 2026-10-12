package cv.sitech.application.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;

public interface AccountRepository {
  Account save(Account account);

  Optional<Account> findById(Long id);

  List<Account> findAll();

  List<Account> findByDfsp(String fspId);

  /** the DFSP's own POSITION or SETTLEMENT account */
  Optional<Account> findDfspAccount(String fspId, AccountTypeEnum type);

  /** loads the account with a row lock held until the current transaction ends */
  Optional<Account> findByIdForUpdate(Long id);

  void updateBalances(Long id, BigDecimal balance, BigDecimal reservedBalance, BigDecimal pendingCredit);

  Boolean deleteById(Long id);
}
