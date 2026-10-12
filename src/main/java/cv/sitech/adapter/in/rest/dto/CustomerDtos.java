package cv.sitech.adapter.in.rest.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import cv.sitech.domain.Contact;
import cv.sitech.domain.Customer;
import cv.sitech.domain.IdTypeEnum;
import cv.sitech.domain.Party;
import cv.sitech.domain.ledger.JournalEntry;

public final class CustomerDtos {

  private CustomerDtos() {
  }

  public record ContactDto(String phone, String telephone, String email) {
    public Contact toDomain() {
      return new Contact(phone, telephone, email);
    }

    public static ContactDto from(Contact c) {
      return c == null ? null : new ContactDto(c.getPhone(), c.getTelephone(), c.getEmail());
    }
  }

  public record CustomerRequest(String name, ContactDto contact, String address, Long nif) {
  }

  public record CustomerView(Long id, String name, ContactDto contact, String address, Long nif) {
    public static CustomerView from(Customer c) {
      return new CustomerView(c.getId(), c.getName(), ContactDto.from(c.getContact()), c.getAddress(), c.getNif());
    }
  }

  public record OpenAccount(Long customerId) {
  }

  public record Deposit(BigDecimal amount) {
  }

  public record PostingView(Long accountId, BigDecimal amount) {
  }

  public record EntryView(Long id, String reference, String type, String status, Instant createdAt,
      List<PostingView> postings) {
    public static EntryView from(JournalEntry e) {
      return new EntryView(e.getId(), e.getReference(), e.getType().name(), e.getStatus().name(), e.getCreatedAt(),
          e.getPostings().stream().map(p -> new PostingView(p.getAccountId(), p.getAmount())).toList());
    }
  }

  public record PartyRequest(IdTypeEnum idType, String idValue, Long accountId) {
  }

  public record PartyView(Long id, IdTypeEnum idType, String idValue, Long accountId) {
    public static PartyView from(Party p) {
      return new PartyView(p.getId(), p.getIdType(), p.getIdValue(), p.getAccount().getId());
    }
  }

}
