package cv.sitech.application.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import cv.sitech.application.port.out.AccountRepository;
import cv.sitech.application.port.out.JournalEntryRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.InsufficientFundsException;
import cv.sitech.domain.exception.NotFoundException;
import cv.sitech.domain.ledger.EntryStatus;
import cv.sitech.domain.ledger.EntryType;
import cv.sitech.domain.ledger.JournalEntry;
import cv.sitech.domain.ledger.Posting;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;

/**
 * Double-entry inner ledger. Every entry's postings sum to zero; a posting's amount is added to the
 * account balance when the entry commits.
 *
 * <p>
 * A RESERVED entry only holds funds: negative postings go to {@code reservedBalance} (reducing the
 * available balance) and positive postings go to {@code pendingCredit} (counting towards the
 * position's net debit cap). Entries are keyed by a unique {@code reference}, which makes every
 * operation idempotent.
 */
@ApplicationScoped
public class LedgerService {

  private final AccountRepository accountRepository;
  private final JournalEntryRepository journalEntryRepository;

  public LedgerService(AccountRepository accountRepository, JournalEntryRepository journalEntryRepository) {
    this.accountRepository = accountRepository;
    this.journalEntryRepository = journalEntryRepository;
  }

  public static Posting posting(Long accountId, BigDecimal amount) {
    Posting posting = new Posting();
    posting.setAccountId(accountId);
    posting.setAmount(amount);
    return posting;
  }

  /** holds funds without moving balances; checks available funds and the payer's net debit cap */
  @Transactional
  public JournalEntry reserve(String reference, EntryType type, List<Posting> postings) {
    var existing = journalEntryRepository.findByReference(reference);
    if (existing.isPresent()) {
      return existing.get();
    }
    validate(postings);
    Map<Long, Account> accounts = lock(postings);
    for (Posting p : postings) {
      Account account = accounts.get(p.getAccountId());
      if (p.getAmount().signum() < 0) {
        checkDebit(account, p.getAmount().negate());
        account.setReservedBalance(account.getReservedBalance().add(p.getAmount().negate()));
      } else {
        checkPositionLimit(account, p.getAmount());
        account.setPendingCredit(account.getPendingCredit().add(p.getAmount()));
      }
    }
    store(accounts);
    return journalEntryRepository.save(entry(reference, type, EntryStatus.RESERVED, postings));
  }

  /** moves balances of a RESERVED entry and releases its holds */
  @Transactional
  public JournalEntry commit(String reference) {
    JournalEntry entry = find(reference);
    if (entry.getStatus() == EntryStatus.COMMITTED) {
      return entry;
    }
    if (entry.getStatus() != EntryStatus.RESERVED) {
      throw new BusinessException("2001", "Ledger entry " + reference + " is " + entry.getStatus());
    }
    Map<Long, Account> accounts = lock(entry.getPostings());
    for (Posting p : entry.getPostings()) {
      Account account = accounts.get(p.getAccountId());
      release(account, p.getAmount());
      account.setBalance(account.getBalance().add(p.getAmount()));
    }
    store(accounts);
    return updateStatus(entry, EntryStatus.COMMITTED);
  }

  /** releases the holds of a RESERVED entry; no-op when the entry is missing or already final */
  @Transactional
  public void abort(String reference) {
    var found = journalEntryRepository.findByReference(reference);
    if (found.isEmpty() || found.get().getStatus() != EntryStatus.RESERVED) {
      return;
    }
    JournalEntry entry = found.get();
    Map<Long, Account> accounts = lock(entry.getPostings());
    for (Posting p : entry.getPostings()) {
      release(accounts.get(p.getAccountId()), p.getAmount());
    }
    store(accounts);
    updateStatus(entry, EntryStatus.ABORTED);
  }

  /** books a committed entry straight away */
  @Transactional
  public JournalEntry post(String reference, EntryType type, List<Posting> postings) {
    var existing = journalEntryRepository.findByReference(reference);
    if (existing.isPresent()) {
      return existing.get();
    }
    validate(postings);
    Map<Long, Account> accounts = lock(postings);
    for (Posting p : postings) {
      Account account = accounts.get(p.getAccountId());
      if (p.getAmount().signum() < 0) {
        checkDebit(account, p.getAmount().negate());
      }
      account.setBalance(account.getBalance().add(p.getAmount()));
    }
    store(accounts);
    return journalEntryRepository.save(entry(reference, type, EntryStatus.COMMITTED, postings));
  }

  /** books the opposite of a COMMITTED entry under {@code reference + ":REVERSAL"} */
  @Transactional
  public void reverse(String reference) {
    JournalEntry entry = find(reference);
    if (entry.getStatus() != EntryStatus.COMMITTED) {
      abort(reference);
      return;
    }
    List<Posting> reversal = entry.getPostings().stream()
        .map(p -> posting(p.getAccountId(), p.getAmount().negate()))
        .toList();
    String reversalReference = reference + ":REVERSAL";
    if (journalEntryRepository.findByReference(reversalReference).isPresent()) {
      return;
    }
    Map<Long, Account> accounts = lock(reversal);
    for (Posting p : reversal) {
      Account account = accounts.get(p.getAccountId());
      account.setBalance(account.getBalance().add(p.getAmount()));
    }
    store(accounts);
    journalEntryRepository.save(entry(reversalReference, entry.getType(), EntryStatus.COMMITTED, reversal));
  }

  private void validate(List<Posting> postings) {
    if (postings.size() < 2) {
      throw new IllegalArgumentException("A ledger entry needs at least two postings");
    }
    BigDecimal sum = postings.stream().map(Posting::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    if (sum.signum() != 0) {
      throw new IllegalArgumentException("Ledger entry postings must sum to zero, got " + sum);
    }
  }

  private void checkDebit(Account account, BigDecimal amount) {
    if (account.getType() == AccountTypeEnum.CUSTOMER && account.availableBalance().compareTo(amount) < 0) {
      throw new InsufficientFundsException("Account " + account.getId() + " has insufficient funds");
    }
  }

  private void checkPositionLimit(Account account, BigDecimal amount) {
    if (account.getType() != AccountTypeEnum.POSITION) {
      return;
    }
    BigDecimal exposure = account.getBalance().add(account.getPendingCredit()).add(amount);
    if (exposure.compareTo(account.getDfsp().getNetDebitCap()) > 0) {
      throw new BusinessException("4200", "Net debit cap of " + account.getDfsp().getFspId() + " exceeded");
    }
  }

  private void release(Account account, BigDecimal amount) {
    if (amount.signum() < 0) {
      account.setReservedBalance(account.getReservedBalance().add(amount));
    } else {
      account.setPendingCredit(account.getPendingCredit().subtract(amount));
    }
  }

  // locks in ascending id order so concurrent entries cannot deadlock
  private Map<Long, Account> lock(List<Posting> postings) {
    Map<Long, Account> accounts = new TreeMap<>();
    postings.stream().map(Posting::getAccountId).sorted().distinct().forEach(id -> accounts.put(id,
        accountRepository.findByIdForUpdate(id)
            .orElseThrow(() -> new NotFoundException("Account " + id + " not Found"))));
    return accounts;
  }

  private void store(Map<Long, Account> accounts) {
    accounts.values().forEach(a -> accountRepository.updateBalances(a.getId(), a.getBalance(),
        a.getReservedBalance(), a.getPendingCredit()));
  }

  private JournalEntry find(String reference) {
    return journalEntryRepository.findByReference(reference)
        .orElseThrow(() -> new NotFoundException("Ledger entry " + reference + " not Found"));
  }

  private JournalEntry updateStatus(JournalEntry entry, EntryStatus status) {
    entry.setStatus(status);
    entry.setUpdatedAt(Instant.now());
    return journalEntryRepository.save(entry);
  }

  private static JournalEntry entry(String reference, EntryType type, EntryStatus status, List<Posting> postings) {
    JournalEntry entry = new JournalEntry();
    entry.setReference(reference);
    entry.setType(type);
    entry.setStatus(status);
    entry.setCreatedAt(Instant.now());
    entry.setUpdatedAt(entry.getCreatedAt());
    entry.setPostings(new ArrayList<>(postings));
    return entry;
  }

}
