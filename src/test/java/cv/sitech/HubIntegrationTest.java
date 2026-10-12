package cv.sitech;

import static cv.sitech.testhub.Fixtures.BANK;
import static cv.sitech.testhub.Fixtures.WALLET;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.SettlementUseCase;
import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.settlement.ReconciliationKind;
import cv.sitech.domain.settlement.ReconciliationStatus;
import cv.sitech.domain.settlement.SettlementReconciliation;
import cv.sitech.domain.transfer.TransferState;
import cv.sitech.testhub.FakeHubResource;
import cv.sitech.testhub.FakeSdkResource;
import cv.sitech.testhub.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;

@QuarkusTest
class HubIntegrationTest {

  private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  @Inject
  Fixtures fixtures;

  @Inject
  OutboundTransferUseCase outbound;

  @Inject
  SettlementUseCase settlements;

  @Inject
  AccountUseCase accounts;

  @BeforeEach
  void setUp() {
    fixtures.dfsp(BANK, new BigDecimal("1000000"));
    fixtures.dfsp(WALLET, new BigDecimal("1000000"));
  }

  @Test
  @TestSecurity(user = "admin", roles = "admin")
  void onboardingPointsTheHubAtTheDfspsSdk() {
    FakeHubResource.ADMIN_CALLS.clear();
    given().when().post("/api/admin/dfsps/" + WALLET + "/onboard").then().statusCode(204);

    List<String> calls = FakeHubResource.ADMIN_CALLS;
    assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST /participants {")
        && c.contains("\"name\":\"" + WALLET + "\"")), calls::toString);
    assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST /participants/" + WALLET + "/initialPositionAndLimits")
        && c.contains("NET_DEBIT_CAP")), calls::toString);
    assertEquals(15, calls.stream().filter(c -> c.startsWith("POST /participants/" + WALLET + "/endpoints")).count());
    assertTrue(calls.stream().anyMatch(c -> c.contains("FSPIOP_CALLBACK_URL_TRANSFER_PUT")
        && c.contains("http://" + WALLET + "-sdk:4000/transfers/{{transferId}}")), calls::toString);
    assertTrue(calls.stream().anyMatch(c -> c.contains("FSPIOP_CALLBACK_URL_BULK_TRANSFER_PUT")
        && c.contains("http://" + WALLET + "-sdk:4000/bulkTransfers/{{id}}")), calls::toString);
    assertTrue(calls.stream().anyMatch(c -> c.startsWith("PUT /participants/" + WALLET + " ")
        && c.contains("isActive")), calls::toString);
    assertTrue(calls.stream().anyMatch(c -> c.startsWith("POST /participants/" + WALLET + "/accounts/set-" + WALLET)
        && c.contains("recordFundsIn")), calls::toString);
  }

  @Test
  @TestSecurity(user = "operator", roles = "dfsp-operator")
  void adminApiNeedsTheAdminRole() {
    given().when().get("/api/admin/dfsps").then().statusCode(403);
  }

  @Test
  void adminApiNeedsAuthentication() {
    given().when().get("/api/admin/dfsps").then().statusCode(401);
  }

  @Test
  void backendAnswersUnknownPartiesWith3204() {
    given().when().get("/sdk/" + WALLET + "/parties/MSISDN/2389900000")
        .then().statusCode(404).body("statusCode", equalTo("3204"));
  }

  @Test
  void backendRejectsUnsupportedIdTypes() {
    given().when().get("/sdk/" + WALLET + "/parties/NOSUCHTYPE/2389900000")
        .then().statusCode(400).body("statusCode", equalTo("3100"));
  }

  @Test
  void backendDoesNotAnswerForPartiesOfAnotherDfsp() {
    String msisdn = Fixtures.msisdn();
    fixtures.customer(BANK, "Lia", msisdn, BigDecimal.ZERO);
    given().when().get("/sdk/" + WALLET + "/parties/MSISDN/" + msisdn)
        .then().statusCode(404).body("statusCode", equalTo("3204"));
  }

  @Test
  void backendRejectsTransfersWithoutAQuote() {
    given().contentType("application/json")
        .body("{\"transferId\":\"t-1\",\"quote\":{\"quoteId\":\"nosuchquote\"},\"amount\":\"1\",\"currency\":\"CVE\"}")
        .when().post("/sdk/" + WALLET + "/transfers")
        .then().statusCode(400).body("statusCode", equalTo("3208"));
  }

  @Test
  void reconciliationMatchesHubPositionsAndBooksSettledAmounts() {
    String payer = Fixtures.msisdn();
    String payee = Fixtures.msisdn();
    fixtures.customer(BANK, "Rita", payer, new BigDecimal("100"));
    fixtures.customer(WALLET, "Rui", payee, BigDecimal.ZERO);
    Instant windowOpen = Instant.now().minusSeconds(1);

    // the fake hub only knows about transfers it routed, so start from the ledger's view of positions
    FakeHubResource.POSITIONS.put(BANK, positionOf(BANK));
    FakeHubResource.POSITIONS.put(WALLET, positionOf(WALLET));

    var transfer = outbound.start(BANK, new StartTransferCommand(IdTypeEnum.MSISDN, payer, IdTypeEnum.MSISDN, payee,
        new BigDecimal("40"), CurrencyEnum.CVE, null, true));
    await().atMost(Duration.ofSeconds(15)).until(
        () -> outbound.findById(BANK, transfer.getTransferId()).getState() == TransferState.COMMITTED);
    Instant windowClose = Instant.now().plusSeconds(1);

    String settlementId = "s-" + System.nanoTime();
    FakeHubResource.settlementsJson = """
        [{"id":"%s","state":"SETTLED",
          "settlementWindows":[{"id":"w1","createdDate":"%s","changedDate":"%s"}],
          "participants":[{"id":1,"accounts":[{"id":"pos-%s","netSettlementAmount":{"amount":"40","currency":"CVE"}}]},
                          {"id":2,"accounts":[{"id":"pos-%s","netSettlementAmount":{"amount":"-40","currency":"CVE"}}]}]}]
        """.formatted(settlementId, TIMESTAMP.format(windowOpen), TIMESTAMP.format(windowClose), BANK, WALLET);
    BigDecimal bankPositionBefore = positionOf(BANK);

    List<SettlementReconciliation> results = settlements.reconcile();

    SettlementReconciliation bankPosition = find(results, ReconciliationKind.POSITION, BANK);
    assertEquals(ReconciliationStatus.MATCH, bankPosition.getStatus(), () -> describe(bankPosition));
    SettlementReconciliation bankSettlement = find(results, ReconciliationKind.SETTLEMENT, BANK);
    assertEquals(ReconciliationStatus.MATCH, bankSettlement.getStatus(), () -> describe(bankSettlement));
    assertTrue(bankSettlement.isPostedToLedger());
    assertEquals(ReconciliationStatus.MATCH, find(results, ReconciliationKind.SETTLEMENT, WALLET).getStatus());
    // settling moved the net amount off the position
    assertEquals(0, bankPositionBefore.subtract(new BigDecimal("40")).compareTo(positionOf(BANK)));

    // a second run does not book the settlement twice
    settlements.reconcile();
    assertEquals(0, bankPositionBefore.subtract(new BigDecimal("40")).compareTo(positionOf(BANK)));
    FakeHubResource.settlementsJson = "[]";
  }

  @Test
  void partyRegistrationGoesThroughTheSdkAndIsDiscoverable() {
    String msisdn = Fixtures.msisdn();
    fixtures.customer(WALLET, "Ines Tavares", msisdn, BigDecimal.ZERO);
    assertEquals(WALLET, FakeSdkResource.ORACLE.get("MSISDN/" + msisdn));
    given().when().get("/sdk/" + WALLET + "/parties/MSISDN/" + msisdn)
        .then().statusCode(200)
        .body("displayName", equalTo("Ines Tavares"))
        .body("firstName", equalTo("Ines"))
        .body("lastName", equalTo("Tavares"))
        .body("fspId", equalTo(WALLET));
  }

  private BigDecimal positionOf(String fspId) {
    return accounts.findAll(fspId).stream()
        .filter(a -> a.getType() == AccountTypeEnum.POSITION).findFirst().orElseThrow()
        .getBalance();
  }

  private static SettlementReconciliation find(List<SettlementReconciliation> results, ReconciliationKind kind,
      String fspId) {
    return results.stream().filter(r -> r.getKind() == kind && r.getFspId().equals(fspId)).findFirst()
        .orElseThrow(() -> new AssertionError("No " + kind + " reconciliation for " + fspId));
  }

  private static String describe(SettlementReconciliation r) {
    return r.getKind() + " " + r.getFspId() + " hub=" + r.getHubAmount() + " internal=" + r.getInternalAmount();
  }

}
