package cv.sitech.domain.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class JournalEntry {
  private Long id;
  private String reference;
  private EntryType type;
  private EntryStatus status;
  private Instant createdAt;
  private Instant updatedAt;
  private List<Posting> postings = new ArrayList<>();

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getReference() {
    return reference;
  }

  public void setReference(String reference) {
    this.reference = reference;
  }

  public EntryType getType() {
    return type;
  }

  public void setType(EntryType type) {
    this.type = type;
  }

  public EntryStatus getStatus() {
    return status;
  }

  public void setStatus(EntryStatus status) {
    this.status = status;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  public List<Posting> getPostings() {
    return postings;
  }

  public void setPostings(List<Posting> postings) {
    this.postings = postings;
  }

}
