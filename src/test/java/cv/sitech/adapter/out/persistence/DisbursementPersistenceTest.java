package cv.sitech.adapter.out.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import cv.sitech.application.port.out.DisbursementItemRepository;
import cv.sitech.application.port.out.DisbursementRepository;
import cv.sitech.domain.Account;
import cv.sitech.domain.CurrencyEnum;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.disbursement.Disbursement;
import cv.sitech.domain.disbursement.DisbursementItem;
import cv.sitech.domain.disbursement.DisbursementItemState;
import cv.sitech.domain.disbursement.DisbursementMode;
import cv.sitech.domain.disbursement.DisbursementState;
import cv.sitech.testhub.Fixtures;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

@QuarkusTest
@TestProfile(DisbursementPersistenceTest.Profile.class)
class DisbursementPersistenceTest {

  /** the disbursement job would claim the QUEUED items these tests lock */
  public static class Profile implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("simulator.disbursement.every", "off");
    }
  }

  private static final String FSP = "g2pbank";

  @Inject
  DisbursementRepository disbursements;

  @Inject
  DisbursementItemRepository items;

  @Inject
  Fixtures fixtures;

  @Test
  void roundTripsBatchAndItems() {
    String id = QuarkusTransaction.requiringNew().call(() -> {
      Disbursement d = disbursements.save(disbursement());
      items.save(item(d.getDisbursementId(), 2, DisbursementItemState.PENDING));
      items.save(item(d.getDisbursementId(), 1, DisbursementItemState.PENDING));
      return d.getDisbursementId();
    });

    QuarkusTransaction.requiringNew().run(() -> {
      Disbursement d = disbursements.findByDisbursementId(id).orElseThrow();
      assertEquals(DisbursementState.CREATED, d.getState());
      assertEquals(CurrencyEnum.CVE, d.getCurrency());
      assertEquals(IdTypeEnum.MSISDN, d.getPayerIdType());
      assertTrue(disbursements.findByPayer(FSP).stream().anyMatch(x -> x.getDisbursementId().equals(id)));

      List<DisbursementItem> found = items.findByDisbursementId(id);
      assertEquals(List.of(1, 2), found.stream().map(DisbursementItem::getLineNo).toList());
      assertEquals(DisbursementItemState.PENDING, found.get(0).getState());
      assertEquals(found.get(0).getId(),
          items.findByHomeTransactionId(found.get(0).getHomeTransactionId()).orElseThrow().getId());
    });
  }

  /** a second worker skips the QUEUED items the first one holds instead of waiting for them */
  @Test
  void lockQueuedSkipsLockedItems() throws Exception {
    String id = QuarkusTransaction.requiringNew().call(() -> {
      Disbursement d = disbursements.save(disbursement());
      for (int line = 1; line <= 4; line++) {
        items.save(item(d.getDisbursementId(), line, DisbursementItemState.QUEUED));
      }
      return d.getDisbursementId();
    });

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    CompletableFuture<List<Long>> first = CompletableFuture.supplyAsync(() -> QuarkusTransaction.requiringNew()
        .call(() -> {
          List<Long> ids = ids(items.lockQueued(2), id);
          locked.countDown();
          // without SKIP LOCKED the second worker blocks on these rows and never releases this one
          if (!release.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("second worker blocked on locked items");
          }
          return ids;
        }));
    assertTrue(locked.await(10, TimeUnit.SECONDS));

    List<Long> second = QuarkusTransaction.requiringNew().call(() -> ids(items.lockQueued(100), id));
    release.countDown();
    List<Long> firstIds = first.get(10, TimeUnit.SECONDS);

    assertEquals(2, firstIds.size());
    assertEquals(2, second.size());
    assertTrue(second.stream().noneMatch(firstIds::contains));
  }

  /** other tests may leave QUEUED items behind, so only this batch's items count */
  private List<Long> ids(List<DisbursementItem> locked, String disbursementId) {
    return locked.stream().filter(i -> i.getDisbursementId().equals(disbursementId)).map(DisbursementItem::getId)
        .toList();
  }

  private Disbursement disbursement() {
    fixtures.dfsp(FSP, new BigDecimal("100000"));
    String msisdn = Fixtures.msisdn();
    Account payer = fixtures.customer(FSP, "Ministerio Financas", msisdn, BigDecimal.ZERO);
    Instant now = Instant.now();
    Disbursement d = new Disbursement();
    d.setDisbursementId(UUID.randomUUID().toString());
    d.setPayerFspId(FSP);
    d.setPayerIdType(IdTypeEnum.MSISDN);
    d.setPayerIdValue(msisdn);
    d.setPayerAccountId(payer.getId());
    d.setCurrency(CurrencyEnum.CVE);
    d.setState(DisbursementState.CREATED);
    d.setMode(DisbursementMode.INDIVIDUAL);
    d.setTotalAmount(BigDecimal.ZERO);
    d.setCommittedAmount(BigDecimal.ZERO);
    d.setCreatedAt(now);
    d.setUpdatedAt(now);
    return d;
  }

  private static DisbursementItem item(String disbursementId, int line, DisbursementItemState state) {
    Instant now = Instant.now();
    DisbursementItem i = new DisbursementItem();
    i.setDisbursementId(disbursementId);
    i.setLineNo(line);
    i.setPayeeIdType(IdTypeEnum.MSISDN);
    i.setPayeeIdValue(Fixtures.msisdn());
    i.setAmount(new BigDecimal("10"));
    i.setState(state);
    i.setHomeTransactionId(UUID.randomUUID().toString());
    i.setCreatedAt(now);
    i.setUpdatedAt(now);
    return i;
  }

}
