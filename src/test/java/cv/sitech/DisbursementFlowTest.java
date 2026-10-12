package cv.sitech;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.DisbursementUseCase;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.application.port.out.DisbursementItemRepository;
import cv.sitech.application.port.out.DisbursementRepository;
import cv.sitech.application.port.out.TransferRepository;
import cv.sitech.application.service.PayerService;
import cv.sitech.domain.Account;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;
import cv.sitech.domain.disbursement.DisbursementState;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferState;
import cv.sitech.testhub.FakeSdkResource;
import cv.sitech.testhub.Fixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * G2P batches paid by a {@code g2pgov} government account to {@code g2pwallet} customers, each
 * item as its own transfer through the fake SDK. The scheduler (every second in tests) does the
 * lookups and the payments, so the tests poll.
 */
@QuarkusTest
@TestSecurity(user = "bank-user")
@OidcSecurity(claims = @Claim(key = "fsp_id", value = DisbursementFlowTest.GOV))
class DisbursementFlowTest {

  // DFSPs of their own: payments to testbank/testwallet would land in HubIntegrationTest's settlement window
  static final String GOV = "g2pgov";
  static final String BENEFICIARY_FSP = "g2pwallet";

  @Inject
  Fixtures fixtures;

  @Inject
  DisbursementUseCase disbursements;

  @Inject
  DisbursementRepository disbursementRepository;

  @Inject
  DisbursementItemRepository itemRepository;

  @Inject
  TransferRepository transferRepository;

  @Inject
  OutboundTransferUseCase outbound;

  @Inject
  PayerService payerService;

  String governmentMsisdn;
  Account government;
  List<String> beneficiaries;
  List<Account> beneficiaryAccounts;

  @BeforeEach
  void setUp() {
    fixtures.dfsp(GOV, new BigDecimal("1000000"));
    fixtures.dfsp(BENEFICIARY_FSP, new BigDecimal("1000000"));
    governmentMsisdn = Fixtures.msisdn();
    government = fixtures.customer(GOV, "Ministerio das Financas", governmentMsisdn, new BigDecimal("1000"));
    beneficiaries = new ArrayList<>();
    beneficiaryAccounts = new ArrayList<>();
    for (String name : List.of("Ana Tavares", "Joao Lopes", "Rosa Semedo")) {
      String msisdn = Fixtures.msisdn();
      beneficiaries.add(msisdn);
      beneficiaryAccounts.add(fixtures.customer(BENEFICIARY_FSP, name, msisdn, BigDecimal.ZERO));
    }
  }

  @Test
  void validatedBatchPaysTheValidItemsAndSkipsTheUnknownPayee() {
    String id = create(beneficiaries.get(0), "100", beneficiaries.get(1), "150.50",
        "2380000000", "10", beneficiaries.get(2), "49.50");

    given().when().put("/api/disbursements/" + id + "/validate")
        .then().statusCode(202).body("state", equalTo("VALIDATING"));
    awaitState(id, DisbursementState.VALIDATED);

    List<DisbursementItem> items = disbursements.items(GOV, id, null);
    assertEquals(List.of(DisbursementItemState.VALID, DisbursementItemState.VALID, DisbursementItemState.INVALID,
        DisbursementItemState.VALID), items.stream().map(DisbursementItem::getState).toList());
    assertEquals("Joao Lopes", items.get(1).getPayeeName());
    assertEquals(BENEFICIARY_FSP, items.get(1).getPayeeFspId());
    assertEquals("3204", items.get(2).getErrorCode());

    given().when().put("/api/disbursements/" + id + "/approve")
        .then().statusCode(202).body("state", equalTo("EXECUTING"));
    Disbursement done = awaitState(id, DisbursementState.PARTIALLY_COMPLETED);

    assertEquals(3, done.getCommittedCount());
    assertAmount("300", done.getCommittedAmount());
    assertEquals(1, done.getFailedCount());
    assertNotNull(done.getCompletedAt());
    assertAmount("700", fixtures.account(GOV, government.getId()).getBalance());
    assertAmount("0", fixtures.account(GOV, government.getId()).getReservedBalance());
    assertAmount("100", fixtures.account(BENEFICIARY_FSP, beneficiaryAccounts.get(0).getId()).getBalance());
    assertAmount("150.50", fixtures.account(BENEFICIARY_FSP, beneficiaryAccounts.get(1).getId()).getBalance());
    assertAmount("49.50", fixtures.account(BENEFICIARY_FSP, beneficiaryAccounts.get(2).getId()).getBalance());

    for (DisbursementItem item : disbursements.items(GOV, id, DisbursementItemState.COMMITTED)) {
      Transfer transfer = outbound.findById(GOV, item.getTransferId());
      assertEquals(TransferState.COMMITTED, transfer.getState());
      assertEquals(item.getHomeTransactionId(), transfer.getHomeTransactionId());
    }
    given().when().get("/api/disbursements/" + id + "/items?state=INVALID")
        .then().statusCode(200).body("size()", equalTo(1)).body("[0].lineNo", equalTo(3));
  }

  @Test
  void batchWithOnlyKnownPayeesCompletes() {
    String id = create(beneficiaries.get(0), "10", beneficiaries.get(1), "20");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);
    disbursements.approve(GOV, id);
    Disbursement done = awaitState(id, DisbursementState.COMPLETED);
    assertEquals(2, done.getCommittedCount());
    assertEquals(0, done.getFailedCount());
    assertAmount("970", fixtures.account(GOV, government.getId()).getBalance());
  }

  @Test
  void approvalNeedsTheWholeBatchCovered() {
    String id = create(beneficiaries.get(0), "600", beneficiaries.get(1), "600");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);

    given().when().put("/api/disbursements/" + id + "/approve")
        .then().statusCode(409).body("code", equalTo("4001"));
    assertEquals(DisbursementState.VALIDATED, disbursements.findById(GOV, id).getState());
    assertTrue(disbursements.items(GOV, id, DisbursementItemState.QUEUED).isEmpty());
  }

  @Test
  void approvalBeforeValidationIsRefused() {
    String id = create(beneficiaries.get(0), "10");
    given().when().put("/api/disbursements/" + id + "/approve")
        .then().statusCode(409).body("code", equalTo("3100"));
  }

  @Test
  void invalidRequestsAreRejected() {
    given().contentType(ContentType.JSON).body(request(beneficiaries.get(0), "-5"))
        .when().post("/api/disbursements")
        .then().statusCode(400).body("message", equalTo("Line 1: Amount must be positive"));
    given().contentType(ContentType.JSON).body(Map.of("from", party(governmentMsisdn), "items", List.of(
        Map.of("to", party(beneficiaries.get(0)), "amount", "1"),
        Map.of("to", party(beneficiaries.get(0)), "amount", "2"))))
        .when().post("/api/disbursements")
        .then().statusCode(400).body("message", equalTo("Line 2 repeats the payee of line 1"));
  }

  @Test
  void cancelledBatchPaysNothing() {
    String id = create(beneficiaries.get(0), "10", beneficiaries.get(1), "20");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);

    given().when().put("/api/disbursements/" + id + "/cancel")
        .then().statusCode(200).body("state", equalTo("CANCELLED"));
    Disbursement cancelled = disbursements.findById(GOV, id);
    assertNotNull(cancelled.getCompletedAt());
    assertEquals(2, disbursements.items(GOV, id, DisbursementItemState.SKIPPED).size());
    assertAmount("1000", fixtures.account(GOV, government.getId()).getBalance());
    given().when().put("/api/disbursements/" + id + "/cancel").then().statusCode(409);
  }

  /** the SDK times out after the hub committed: the item waits for the expiry job to ask the SDK */
  @Test
  void itemWithUnknownOutcomeIsResolvedFromTheTransfer() {
    String payee = beneficiaries.get(0);
    FakeSdkResource.TIMEOUT_AFTER_COMMIT.add(payee);
    try {
      String id = create(payee, "25");
      disbursements.validate(GOV, id);
      awaitState(id, DisbursementState.VALIDATED);
      disbursements.approve(GOV, id);

      DisbursementItem item = disbursements.items(GOV, id, null).get(0);
      Transfer reserved = await().atMost(Duration.ofSeconds(15)).until(
          () -> QuarkusTransaction.requiringNew().call(
              () -> transferRepository.findOutboundByHomeTransactionId(item.getHomeTransactionId())).orElse(null),
          t -> t != null && t.getState() == TransferState.RESERVED);
      // outcome unknown: still running, funds held
      assertEquals(DisbursementState.EXECUTING, disbursements.findById(GOV, id).getState());
      assertEquals(DisbursementItemState.SUBMITTED, disbursements.items(GOV, id, null).get(0).getState());
      assertAmount("25", fixtures.account(GOV, government.getId()).getReservedBalance());

      // what the expiry job does once the transfer is past its expiration: GET /transfers/{id} says COMMITTED
      payerService.expire(reserved);

      Disbursement done = awaitState(id, DisbursementState.COMPLETED);
      assertEquals(1, done.getCommittedCount());
      assertAmount("975", fixtures.account(GOV, government.getId()).getBalance());
      assertAmount("0", fixtures.account(GOV, government.getId()).getReservedBalance());
    } finally {
      FakeSdkResource.TIMEOUT_AFTER_COMMIT.remove(payee);
    }
  }

  /** a crash between claiming an item and starting its transfer leaves it SUBMITTED with no transfer */
  @Test
  void itemThatWasNeverSentIsQueuedAgain() {
    String id = create(beneficiaries.get(0), "30");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);
    QuarkusTransaction.requiringNew().run(() -> {
      Disbursement d = disbursementRepository.findByDisbursementIdForUpdate(id).orElseThrow();
      d.setState(DisbursementState.EXECUTING);
      disbursementRepository.save(d);
      DisbursementItem item = itemRepository.findByDisbursementId(id).get(0);
      item.setState(DisbursementItemState.SUBMITTED);
      item.setUpdatedAt(Instant.now().minus(Duration.ofMinutes(1)));
      itemRepository.save(item);
    });

    Disbursement done = awaitState(id, DisbursementState.COMPLETED);
    assertEquals(1, done.getCommittedCount());
    assertAmount("970", fixtures.account(GOV, government.getId()).getBalance());
  }

  @Test
  void startingTheSameHomeTransactionTwicePaysOnce() {
    String home = UUID.randomUUID().toString();
    var command = new StartTransferCommand(IdTypeEnum.MSISDN, governmentMsisdn, IdTypeEnum.MSISDN,
        beneficiaries.get(0), new BigDecimal("5"), null, null, true, home);
    Transfer first = outbound.start(GOV, command);
    Transfer second = outbound.start(GOV, command);

    assertEquals(TransferState.COMMITTED, first.getState());
    assertEquals(first.getTransferId(), second.getTransferId());
    assertEquals(home, first.getHomeTransactionId());
    assertAmount("995", fixtures.account(GOV, government.getId()).getBalance());
    assertNull(outbound.findAll(GOV).stream()
        .filter(t -> home.equals(t.getHomeTransactionId()) && !t.getTransferId().equals(first.getTransferId()))
        .findAny().orElse(null));
  }

  // --- helpers ---

  /** creates a batch through the API from (payee MSISDN, amount) pairs, in line order */
  private String create(String... payeesAndAmounts) {
    return given().contentType(ContentType.JSON).body(request(payeesAndAmounts))
        .when().post("/api/disbursements")
        .then().statusCode(201).body("state", equalTo("CREATED"))
        .body("itemCount", equalTo(payeesAndAmounts.length / 2))
        .extract().path("disbursementId");
  }

  private Map<String, Object> request(String... payeesAndAmounts) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (int i = 0; i < payeesAndAmounts.length; i += 2) {
      lines.add(Map.of("to", party(payeesAndAmounts[i]), "amount", payeesAndAmounts[i + 1]));
    }
    return Map.of("from", party(governmentMsisdn), "reference", "Subsidio 2026-10", "items", lines);
  }

  private static Map<String, String> party(String msisdn) {
    return Map.of("idType", "MSISDN", "idValue", msisdn);
  }

  private Disbursement awaitState(String id, DisbursementState state) {
    return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
        .until(() -> disbursements.findById(GOV, id), d -> d.getState() == state);
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

}
