package cv.sitech;

import static cv.sitech.testhub.Fixtures.BANK;
import static cv.sitech.testhub.Fixtures.WALLET;
import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.domain.Account;
import cv.sitech.domain.AccountTypeEnum;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferState;
import cv.sitech.testhub.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.security.oidc.Claim;
import io.quarkus.test.security.oidc.OidcSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * Full P2P flows between two hosted DFSPs through the in-process fake SDK: the logged-in
 * {@code testbank} user pays a {@code testwallet} customer.
 */
@QuarkusTest
@TestSecurity(user = "bank-user")
@OidcSecurity(claims = @Claim(key = "fsp_id", value = BANK))
class P2pFlowTest {

  @Inject
  Fixtures fixtures;

  @Inject
  OutboundTransferUseCase outbound;

  @Inject
  PayeeUseCase payee;

  @Inject
  AccountUseCase accounts;

  String payerMsisdn;
  String payeeMsisdn;
  Account payerAccount;
  Account payeeAccount;

  @BeforeEach
  void setUp() {
    fixtures.dfsp(BANK, new BigDecimal("1000000"));
    fixtures.dfsp(WALLET, new BigDecimal("1000000"));
    payerMsisdn = Fixtures.msisdn();
    payeeMsisdn = Fixtures.msisdn();
    payerAccount = fixtures.customer(BANK, "Maria Silva", payerMsisdn, new BigDecimal("500"));
    payeeAccount = fixtures.customer(WALLET, "Joao Lopes", payeeMsisdn, BigDecimal.ZERO);
  }

  @Test
  void autoAcceptedTransferCommitsOnBothSides() {
    BigDecimal bankPosition = position(BANK);
    BigDecimal walletPosition = position(WALLET);

    String transferId = start(payeeMsisdn, "120.50", true);
    Transfer sent = awaitState(transferId, TransferState.COMMITTED);

    assertEquals("Joao Lopes", sent.getPayeeName());
    assertEquals(WALLET, sent.getPayeeFspId());
    assertAmount("379.50", fixtures.account(BANK, payerAccount.getId()).getBalance());
    assertAmount("0", fixtures.account(BANK, payerAccount.getId()).getReservedBalance());
    assertAmount("120.50", fixtures.account(WALLET, payeeAccount.getId()).getBalance());
    assertAmount(bankPosition.add(new BigDecimal("120.50")).toPlainString(), position(BANK));
    assertAmount(walletPosition.subtract(new BigDecimal("120.50")).toPlainString(), position(WALLET));

    Transfer received = payee.findAll(WALLET).stream().filter(t -> transferId.equals(t.getTransferId()))
        .findFirst().orElseThrow();
    assertEquals(TransferState.COMMITTED, received.getState());
    assertEquals(sent.getQuoteId(), received.getQuoteId());
  }

  @Test
  void stepwiseTransferWaitsForPayerAcceptance() {
    String transferId = start(payeeMsisdn, "10", false);
    awaitState(transferId, TransferState.WAITING_FOR_PARTY_ACCEPTANCE);

    given().contentType(ContentType.JSON).body("{\"accept\":true}")
        .when().put("/api/transfers/" + transferId + "/accept-party")
        .then().statusCode(200);
    Transfer quoted = awaitState(transferId, TransferState.WAITING_FOR_QUOTE_ACCEPTANCE);
    assertAmount("10", quoted.getTransferAmount());
    assertAmount("500", fixtures.account(BANK, payerAccount.getId()).getBalance());

    given().contentType(ContentType.JSON).body("{\"accept\":true}")
        .when().put("/api/transfers/" + transferId + "/accept-quote")
        .then().statusCode(200);
    awaitState(transferId, TransferState.COMMITTED);
    assertAmount("490", fixtures.account(BANK, payerAccount.getId()).getBalance());
  }

  @Test
  void rejectedQuoteAbortsWithoutMovingFunds() {
    String transferId = start(payeeMsisdn, "10", false);
    awaitState(transferId, TransferState.WAITING_FOR_PARTY_ACCEPTANCE);
    outbound.acceptParty(BANK, transferId, true);
    awaitState(transferId, TransferState.WAITING_FOR_QUOTE_ACCEPTANCE);

    given().contentType(ContentType.JSON).body("{\"accept\":false}")
        .when().put("/api/transfers/" + transferId + "/accept-quote")
        .then().statusCode(200).body("state", equalTo("ABORTED"));
    assertAmount("500", fixtures.account(BANK, payerAccount.getId()).getBalance());
  }

  @Test
  void unknownPayeeAbortsTheTransfer() {
    String transferId = start("2380000000", "10", true);
    Transfer t = awaitState(transferId, TransferState.ABORTED);
    assertEquals("3204", t.getErrorCode());
  }

  @Test
  void insufficientFundsAbortsBeforeTheTransferIsSent() {
    String transferId = start(payeeMsisdn, "600", true);
    Transfer t = awaitState(transferId, TransferState.ABORTED);
    assertEquals("4001", t.getErrorCode());
    assertAmount("500", fixtures.account(BANK, payerAccount.getId()).getBalance());
    assertAmount("0", fixtures.account(BANK, payerAccount.getId()).getReservedBalance());
  }

  @Test
  void lookupReturnsThePayeeName() {
    given().when().get("/api/parties/lookup/MSISDN/" + payeeMsisdn)
        .then().statusCode(200)
        .body("fspId", equalTo(WALLET))
        .body("name", equalTo("Joao Lopes"));
  }

  @Test
  void lookupOfUnknownPartyIsNotFound() {
    given().when().get("/api/parties/lookup/MSISDN/2380000001")
        .then().statusCode(404).body("code", equalTo("3204"));
  }

  @Test
  void otherDfspsDataIsInvisible() {
    given().when().get("/api/accounts/" + payeeAccount.getId()).then().statusCode(404);
    given().when().get("/api/accounts/" + payerAccount.getId()).then().statusCode(200)
        .body("type", equalTo("CUSTOMER"));
  }

  @Test
  void payerMustBelongToTheLoggedInDfsp() {
    given().contentType(ContentType.JSON)
        .body(transferBody(payeeMsisdn, payerMsisdn, "1", true))
        .when().post("/api/transfers")
        .then().statusCode(404);
  }

  private String start(String toMsisdn, String amount, boolean autoAccept) {
    return given().contentType(ContentType.JSON)
        .body(transferBody(payerMsisdn, toMsisdn, amount, autoAccept))
        .when().post("/api/transfers")
        .then().statusCode(202)
        .extract().path("transferId");
  }

  private static String transferBody(String from, String to, String amount, boolean autoAccept) {
    return """
        {"from":{"idType":"MSISDN","idValue":"%s"},"to":{"idType":"MSISDN","idValue":"%s"},
         "amount":%s,"currency":"CVE","note":"test","autoAccept":%s}""".formatted(from, to, amount, autoAccept);
  }

  private Transfer awaitState(String transferId, TransferState state) {
    return await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
        .until(() -> outbound.findById(BANK, transferId), t -> {
          if (t.getState().isFinal() && t.getState() != state) {
            throw new AssertionError("Transfer ended " + t.getState() + ": " + t.getErrorDescription());
          }
          return t.getState() == state;
        });
  }

  private BigDecimal position(String fspId) {
    return accounts.findAll(fspId).stream().filter(a -> a.getType() == AccountTypeEnum.POSITION).findFirst()
        .orElseThrow().getBalance();
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
  }

}
