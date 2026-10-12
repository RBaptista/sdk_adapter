package cv.sitech.testhub;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.CustomerUseCase;
import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.application.port.in.PartyUseCase;
import cv.sitech.domain.Account;
import cv.sitech.domain.Contact;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.Dfsp;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.exception.NotFoundException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Test data built through the use cases, so it goes through the same rules as the API. */
@ApplicationScoped
public class Fixtures {

  public static final String BANK = "testbank";
  public static final String WALLET = "testwallet";

  @Inject
  DfspUseCase dfsps;

  @Inject
  CustomerUseCase customers;

  @Inject
  AccountUseCase accounts;

  @Inject
  PartyUseCase parties;

  public Dfsp dfsp(String fspId, BigDecimal netDebitCap) {
    try {
      return dfsps.findById(fspId);
    } catch (NotFoundException e) {
      return dfsps.create(fspId, fspId + " display", CurrencyEnum.CVE, FakeSdkResource.outboundUrl(fspId), null,
          netDebitCap, BigDecimal.ZERO, BigDecimal.valueOf(10_000));
    }
  }

  public static String msisdn() {
    return "238" + ThreadLocalRandom.current().nextLong(1_000_000L, 9_999_999L);
  }

  /** a customer account of {@code fspId} with {@code deposit} on it, discoverable as MSISDN {@code msisdn} */
  public Account customer(String fspId, String name, String msisdn, BigDecimal deposit) {
    var customer = customers.create(fspId, name, new Contact(msisdn, null, null), "Praia", null);
    Account account = accounts.open(fspId, customer.getId());
    if (deposit.signum() > 0) {
      account = accounts.deposit(fspId, account.getId(), deposit);
    }
    parties.create(fspId, IdTypeEnum.MSISDN, msisdn, account.getId());
    return account;
  }

  public Account account(String fspId, Long id) {
    return accounts.findById(fspId, id);
  }

}
