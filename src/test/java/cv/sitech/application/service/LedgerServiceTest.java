package cv.sitech.application.service;

import static cv.sitech.application.service.LedgerService.posting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.CustomerUseCase;
import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.exception.BusinessException;
import cv.sitech.domain.exception.InsufficientFundsException;
import cv.sitech.domain.ledger.EntryType;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

@QuarkusTest
@TestTransaction
class LedgerServiceTest {

  private static final String FSP = "ledgerfsp";

  @Inject
  LedgerService ledger;

  @Inject
  DfspUseCase dfsps;

  @Inject
  CustomerUseCase customers;

  @Inject
  AccountUseCase accounts;

  Account wallet;
  Account position;
  Account settlement;

  // @TestTransaction does not cover @BeforeEach, so each test sets up inside its own transaction
  void setUp() {
    dfsps.create(FSP, "Ledger FSP", CurrencyEnum.CVE, "http://localhost:8081/test-sdk/" + FSP, null,
        new BigDecimal("100"), new BigDecimal("5"), null);
    wallet = accounts.open(FSP, customers.create(FSP, "Ana", null, null, null).getId());
    accounts.deposit(FSP, wallet.getId(), new BigDecimal("100"));
    position = byType(AccountTypeEnum.POSITION);
    settlement = byType(AccountTypeEnum.SETTLEMENT);
  }

  @Test
  void depositAndInitialPositionBalanceToZero() {
    setUp();
    assertAmount("100", reload(wallet).getBalance());
    assertAmount("5", reload(position).getBalance());
    assertAmount("-105", reload(settlement).getBalance());
    assertZeroSum();
  }

  @Test
  void reservationHoldsFundsUntilCommit() {
    setUp();
    ledger.reserve("T1", EntryType.TRANSFER_OUT, out("30"));
    assertAmount("100", reload(wallet).getBalance());
    assertAmount("70", reload(wallet).availableBalance());
    assertAmount("30", reload(position).getPendingCredit());

    ledger.commit("T1");
    assertAmount("70", reload(wallet).getBalance());
    assertAmount("0", reload(wallet).getReservedBalance());
    assertAmount("35", reload(position).getBalance());
    assertAmount("0", reload(position).getPendingCredit());
    assertZeroSum();
  }

  @Test
  void abortReleasesTheHold() {
    setUp();
    ledger.reserve("T2", EntryType.TRANSFER_OUT, out("40"));
    ledger.abort("T2");
    assertAmount("100", reload(wallet).availableBalance());
    assertAmount("0", reload(position).getPendingCredit());
    assertZeroSum();
  }

  @Test
  void rejectsDebitBeyondAvailableFunds() {
    setUp();
    ledger.reserve("T3", EntryType.TRANSFER_OUT, out("60"));
    assertThrows(InsufficientFundsException.class, () -> ledger.reserve("T4", EntryType.TRANSFER_OUT, out("50")));
  }

  @Test
  void rejectsPositionBeyondNetDebitCap() {
    setUp();
    accounts.deposit(FSP, wallet.getId(), new BigDecimal("500"));
    // initial position 5 + 90 reserved = 95 <= 100
    ledger.reserve("T5", EntryType.TRANSFER_OUT, out("90"));
    BusinessException e = assertThrows(BusinessException.class,
        () -> ledger.reserve("T6", EntryType.TRANSFER_OUT, out("10")));
    assertEquals("4200", e.getCode());
  }

  @Test
  void operationsAreIdempotent() {
    setUp();
    ledger.reserve("T7", EntryType.TRANSFER_OUT, out("10"));
    ledger.reserve("T7", EntryType.TRANSFER_OUT, out("10"));
    ledger.commit("T7");
    ledger.commit("T7");
    assertAmount("90", reload(wallet).getBalance());
  }

  @Test
  void reverseUndoesACommittedEntry() {
    setUp();
    ledger.post("T8", EntryType.TRANSFER_IN, List.of(posting(position.getId(), new BigDecimal("-20")),
        posting(wallet.getId(), new BigDecimal("20"))));
    ledger.reverse("T8");
    assertAmount("100", reload(wallet).getBalance());
    assertAmount("5", reload(position).getBalance());
  }

  @Test
  void rejectsUnbalancedEntries() {
    setUp();
    assertThrows(IllegalArgumentException.class, () -> ledger.post("T9", EntryType.DEPOSIT,
        List.of(posting(wallet.getId(), BigDecimal.ONE), posting(settlement.getId(), BigDecimal.ZERO))));
  }

  private List<cv.sitech.domain.ledger.Posting> out(String amount) {
    BigDecimal a = new BigDecimal(amount);
    return List.of(posting(wallet.getId(), a.negate()), posting(position.getId(), a));
  }

  private Account byType(AccountTypeEnum type) {
    return accounts.findAll(FSP).stream().filter(a -> a.getType() == type).findFirst().orElseThrow();
  }

  private Account reload(Account account) {
    return accounts.findById(FSP, account.getId());
  }

  private void assertZeroSum() {
    BigDecimal sum = accounts.findAll(FSP).stream().map(Account::getBalance).reduce(BigDecimal.ZERO,
        BigDecimal::add);
    assertAmount("0", sum);
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
  }

}
