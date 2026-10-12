package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.CustomerUseCase;
import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.JournalEntryRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.ledger.JournalEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

@ApplicationScoped
public class AccountService implements AccountUseCase {

  private final AccountRepository accountRepository;
  private final JournalEntryRepository journalEntryRepository;
  private final CustomerUseCase customerUseCase;
  private final DfspUseCase dfspUseCase;
  private final LedgerService ledgerService;

  public AccountService(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository,
      CustomerUseCase customerUseCase, DfspUseCase dfspUseCase, LedgerService ledgerService) {
    this.accountRepository = accountRepository;
    this.journalEntryRepository = journalEntryRepository;
    this.customerUseCase = customerUseCase;
    this.dfspUseCase = dfspUseCase;
    this.ledgerService = ledgerService;
  }

  @Override
  @Transactional
  public Account open(String fspId, Long customerId) {
    return accountRepository.save(new Account(AccountTypeEnum.CUSTOMER, customerUseCase.findById(fspId, customerId),
        dfspUseCase.findById(fspId)));
  }

  @Override
  // balances change in other transactions; never serve them from a long-lived session
  @Transactional
  public Account findById(String fspId, Long id) {
    return accountRepository.findById(id)
        .filter(a -> fspId.equals(a.getDfsp().getFspId()))
        .orElseThrow(() -> new NotFoundException("Account " + id + " not Found"));
  }

  @Override
  @Transactional
  public List<Account> findAll(String fspId) {
    return accountRepository.findByDfsp(fspId);
  }

  @Override
  @Transactional
  public Account deposit(String fspId, Long accountId, BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new IllegalArgumentException("Deposit amount must be positive");
    }
    Account account = findById(fspId, accountId);
    if (account.getType() != AccountTypeEnum.CUSTOMER) {
      throw new IllegalArgumentException("Deposits go to CUSTOMER accounts");
    }
    Account settlement = accountRepository.findDfspAccount(fspId, AccountTypeEnum.SETTLEMENT)
        .orElseThrow(() -> new NotFoundException("Settlement account of " + fspId + " not Found"));
    ledgerService.post("DEPOSIT:" + UUID.randomUUID(), EntryType.DEPOSIT,
        List.of(posting(account.getId(), amount), posting(settlement.getId(), amount.negate())));
    return findById(fspId, accountId);
  }

  @Override
  @Transactional
  public List<JournalEntry> statement(String fspId, Long accountId) {
    return journalEntryRepository.findByAccount(findById(fspId, accountId).getId());
  }

}
