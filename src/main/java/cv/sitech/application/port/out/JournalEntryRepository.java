package cv.sitech.application.port.out;

import java.util.List;
import java.util.Optional;

import cv.sitech.domain.ledger.JournalEntry;

public interface JournalEntryRepository {
  JournalEntry save(JournalEntry entry);

  Optional<JournalEntry> findByReference(String reference);

  List<JournalEntry> findByAccount(Long accountId);
}
