package cv.sitech;

import static cv.sitech.testhub.Fixtures.BANK;
import static cv.sitech.testhub.Fixtures.WALLET;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import cv.sitech.application.port.in.OutboundTransferUseCase;
import cv.sitech.application.port.in.PayeeUseCase;
import cv.sitech.application.port.in.StartTransferCommand;
import cv.sitech.domain.Account;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.transfer.Transfer;
import cv.sitech.domain.transfer.TransferState;
import cv.sitech.testhub.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

/** The payee answers RESERVED, charges a 1% fee and only credits once the hub's PATCH arrives. */
@QuarkusTest
@TestProfile(PayeeReservedModeTest.Profile.class)
class PayeeReservedModeTest {

  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("simulator.payee.transfer-state", "RESERVED", "simulator.payee.fee-percent", "1");
    }
  }

  @Inject
  Fixtures fixtures;

  @Inject
  OutboundTransferUseCase outbound;

  @Inject
  PayeeUseCase payee;

  @Test
  void payeeCommitsOnHubNotificationAndKeepsTheFee() {
    fixtures.dfsp(BANK, new BigDecimal("1000000"));
    fixtures.dfsp(WALLET, new BigDecimal("1000000"));
    String payerMsisdn = Fixtures.msisdn();
    String payeeMsisdn = Fixtures.msisdn();
    Account payer = fixtures.customer(BANK, "Maria", payerMsisdn, new BigDecimal("500"));
    Account payeeAccount = fixtures.customer(WALLET, "Joao", payeeMsisdn, BigDecimal.ZERO);

    Transfer started = outbound.start(BANK, new StartTransferCommand(IdTypeEnum.MSISDN, payerMsisdn,
        IdTypeEnum.MSISDN, payeeMsisdn, new BigDecimal("200"), CurrencyEnum.CVE, null, true));
    await().atMost(Duration.ofSeconds(15))
        .until(() -> outbound.findById(BANK, started.getTransferId()).getState() == TransferState.COMMITTED);
    Transfer received = await().atMost(Duration.ofSeconds(15)).until(
        () -> payee.findAll(WALLET).stream().filter(t -> started.getTransferId().equals(t.getTransferId()))
            .findFirst().orElse(null),
        t -> t != null && t.getState() == TransferState.COMMITTED);

    // SEND 200: the payer pays 200, the payee DFSP keeps 1% and credits 198
    assertAmount("2", received.getPayeeFee());
    assertAmount("300", fixtures.account(BANK, payer.getId()).getBalance());
    assertAmount("198", fixtures.account(WALLET, payeeAccount.getId()).getBalance());
    assertAmount("0", fixtures.account(WALLET, payeeAccount.getId()).getPendingCredit());
  }

  private static void assertAmount(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual), () -> "expected " + expected + " but was " + actual);
  }

}
