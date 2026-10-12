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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.CreateDisbursementCommand;
import cv.sitech.application.port.in.DisbursementUseCase;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.application.port.out.DfspRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;
import cv.sitech.domain.disbursement.DisbursementMode;
import cv.sitech.domain.disbursement.DisbursementState;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferState;
import cv.sitech.testhub.Fixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * BULK batches through the fake SDK's {@code /bulkTransactions}: the party lookups come back as the SDK's
 * WAITING_FOR_PARTY_ACCEPTANCE report, approval accepts the parties, the bulk quotes come back as
 * WAITING_FOR_QUOTE_ACCEPTANCE (the app reserves and accepts them), and the bulk transfers as COMPLETED.
 */
@QuarkusTest
@TestProfile(BulkDisbursementFlowTest.Profile.class)
@TestSecurity(user = "bank-user")
@OidcSecurity(claims = @Claim(key = "fsp_id", value = BulkDisbursementFlowTest.GOV))
class BulkDisbursementFlowTest {

  static final String GOV = "g2pbulkgov";
  static final String BENEFICIARY_FSP = "g2pbulkwallet";

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("simulator.disbursement.mode", "BULK");
    }
  }

  @Inject
  Fixtures fixtures;

  @Inject
  DisbursementUseCase disbursements;

  @Inject
  OutboundTransferUseCase outbound;

  @Inject
  PayeeUseCase payee;

  @Inject
  DfspRepository dfspRepository;

  @Inject
  AccountUseCase accounts;

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
  void bulkTransactionPaysTheValidItemsThroughBulkQuotesAndTransfers() {
    BigDecimal govPosition = position(GOV);
    BigDecimal walletPosition = position(BENEFICIARY_FSP);
    String id = create(beneficiaries.get(0), "100", beneficiaries.get(1), "150.50", "2380000000", "10",
        beneficiaries.get(2), "49.50");
    assertEquals(DisbursementMode.BULK, disbursements.findById(GOV, id).getMode());

    given().when().put("/api/disbursements/" + id + "/validate")
        .then().statusCode(202).body("state", equalTo("VALIDATING"));
    awaitState(id, DisbursementState.VALIDATED);

    List<DisbursementItem> items = disbursements.items(GOV, id, null);
    assertEquals(List.of(DisbursementItemState.VALID, DisbursementItemState.VALID, DisbursementItemState.INVALID,
        DisbursementItemState.VALID), items.stream().map(DisbursementItem::getState).toList());
    assertEquals("Joao Lopes", items.get(1).getPayeeName());
    assertEquals(BENEFICIARY_FSP, items.get(1).getPayeeFspId());
    // the SDK assigned every transfer id, the unknown payee's too
    assertNotNull(items.get(0).getTransferId());
    assertEquals("3204", items.get(2).getErrorCode());

    given().when().put("/api/disbursements/" + id + "/approve")
        .then().statusCode(202).body("state", equalTo("EXECUTING"));
    Disbursement done = awaitState(id, DisbursementState.PARTIALLY_COMPLETED);

    assertEquals(3, done.getCommittedCount());
    assertAmount("300", done.getCommittedAmount());
    assertEquals(1, done.getFailedCount());
    assertAmount("700", fixtures.account(GOV, government.getId()).getBalance());
    assertAmount("0", fixtures.account(GOV, government.getId()).getReservedBalance());
    assertAmount("100", fixtures.account(BENEFICIARY_FSP, beneficiaryAccounts.get(0).getId()).getBalance());
    assertAmount("150.50", fixtures.account(BENEFICIARY_FSP, beneficiaryAccounts.get(1).getId()).getBalance());
    assertAmount("49.50", fixtures.account(BENEFICIARY_FSP, beneficiaryAccounts.get(2).getId()).getBalance());
    assertAmount(govPosition.add(new BigDecimal("300")).toPlainString(), position(GOV));
    assertAmount(walletPosition.subtract(new BigDecimal("300")).toPlainString(), position(BENEFICIARY_FSP));

    // both sides keep an ordinary transfer row per item, so settlement reconciliation sees them
    for (DisbursementItem item : disbursements.items(GOV, id, DisbursementItemState.COMMITTED)) {
      Transfer sent = outbound.findById(GOV, item.getTransferId());
      assertEquals(TransferState.COMMITTED, sent.getState());
      assertEquals(item.getHomeTransactionId(), sent.getHomeTransactionId());
      assertEquals("fake-fulfilment", sent.getFulfilment());
      Transfer received = payee.findAll(BENEFICIARY_FSP).stream()
          .filter(t -> item.getTransferId().equals(t.getTransferId())).findFirst().orElseThrow();
      assertEquals(TransferState.COMMITTED, received.getState());
    }
  }

  @Test
  void bulkBatchCompletesWhenEveryPayeeIsKnown() {
    String id = create(beneficiaries.get(0), "10", beneficiaries.get(1), "20");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);
    disbursements.approve(GOV, id);
    Disbursement done = awaitState(id, DisbursementState.COMPLETED);
    assertEquals(2, done.getCommittedCount());
    assertAmount("970", fixtures.account(GOV, government.getId()).getBalance());
  }

  @Test
  void bulkBatchIsValidatedOnlyOnce() {
    String id = create(beneficiaries.get(0), "10");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);
    given().when().put("/api/disbursements/" + id + "/validate")
        .then().statusCode(409).body("code", equalTo("3100"));
  }

  @Test
  void cancelledBulkBatchPaysNothing() {
    String id = create(beneficiaries.get(0), "10", beneficiaries.get(1), "20");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);

    given().when().put("/api/disbursements/" + id + "/cancel")
        .then().statusCode(200).body("state", equalTo("CANCELLED"));
    assertEquals(2, disbursements.items(GOV, id, DisbursementItemState.SKIPPED).size());
    assertAmount("1000", fixtures.account(GOV, government.getId()).getBalance());
  }

  @Test
  void bulkApprovalNeedsTheWholeBatchCovered() {
    String id = create(beneficiaries.get(0), "600", beneficiaries.get(1), "600");
    disbursements.validate(GOV, id);
    awaitState(id, DisbursementState.VALIDATED);
    given().when().put("/api/disbursements/" + id + "/approve")
        .then().statusCode(409).body("code", equalTo("4001"));
    assertTrue(disbursements.items(GOV, id, DisbursementItemState.QUOTING).isEmpty());
  }

  /** the SDK can't be reached: the batch goes back to CREATED so it can be validated again */
  @Test
  void validationIsUndoneWhenTheSdkIsUnreachable() {
    String fsp = "g2pbulkdown";
    fixtures.dfsp(fsp, new BigDecimal("1000"));
    String msisdn = Fixtures.msisdn();
    fixtures.customer(fsp, "Camara Municipal", msisdn, new BigDecimal("100"));
    // registered while the SDK was up; now it is down
    QuarkusTransaction.requiringNew().run(() -> {
      Dfsp dfsp = dfspRepository.findById(fsp).orElseThrow();
      dfsp.setSdkOutboundUrl("http://localhost:1/unreachable");
      dfspRepository.save(dfsp);
    });
    var command = new CreateDisbursementCommand(IdTypeEnum.MSISDN, msisdn, null, null, null,
        List.of(new CreateDisbursementCommand.Item(IdTypeEnum.MSISDN, beneficiaries.get(0), new BigDecimal("5"), null)));
    String id = disbursements.create(fsp, command).getDisbursementId();

    try {
      disbursements.validate(fsp, id);
    } catch (RuntimeException expected) {
      // SdkCallException 1001
    }
    assertEquals(DisbursementState.CREATED, disbursements.findById(fsp, id).getState());
    assertNull(disbursements.items(fsp, id, null).get(0).getTransferId());
  }

  // --- helpers ---

  private String create(String... payeesAndAmounts) {
    List<Map<String, Object>> lines = new ArrayList<>();
    for (int i = 0; i < payeesAndAmounts.length; i += 2) {
      lines.add(Map.of("to", party(payeesAndAmounts[i]), "amount", payeesAndAmounts[i + 1]));
    }
    return given().contentType(ContentType.JSON)
        .body(Map.of("from", party(governmentMsisdn), "reference", "Subsidio 2026-10", "items", lines))
        .when().post("/api/disbursements")
        .then().statusCode(201).body("state", equalTo("CREATED"))
        .extract().path("disbursementId");
  }

  private static Map<String, String> party(String msisdn) {
    return Map.of("idType", "MSISDN", "idValue", msisdn);
  }

  private BigDecimal position(String fspId) {
    return accounts.findAll(fspId).stream().filter(a -> a.getType() == AccountTypeEnum.POSITION).findFirst()
        .orElseThrow().getBalance();
  }

  private Disbursement awaitState(String id, DisbursementState state) {
    return await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
        .until(() -> disbursements.findById(GOV, id), d -> d.getState() == state);
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
  }

}
