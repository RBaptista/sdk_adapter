package cv.sitech.adapter.out.persistence;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.AccountEntity;
import cv.sitech.adapter.out.persistence.mapper.AccountMapper;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.LockModeType;

@ApplicationScoped
public class AccountPersistenceAdapter extends PanacheAdapter<Account, AccountEntity, Long> implements AccountRepository {

  protected AccountPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public AccountPersistenceAdapter(AccountPanacheRepository panache, AccountMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public List<Account> findByDfsp(String fspId) {
    return list("dfsp.fspId", fspId);
  }

  @Override
  public Optional<Account> findDfspAccount(String fspId, AccountTypeEnum type) {
    return first("dfsp.fspId = ?1 and type = ?2", fspId, type.name());
  }

  @Override
  public Optional<Account> findByIdForUpdate(Long id) {
    return panache.findByIdOptional(id, LockModeType.PESSIMISTIC_WRITE).map(mapper::toDomain);
  }

  @Override
  public void updateBalances(Long id, BigDecimal balance, BigDecimal reservedBalance, BigDecimal pendingCredit) {
    AccountEntity entity = panache.findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("Account " + id + " not Found"));
    entity.setBalance(balance);
    entity.setReservedBalance(reservedBalance);
    entity.setPendingCredit(pendingCredit);
  }

}
