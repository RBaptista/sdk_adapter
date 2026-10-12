package cv.sitech.adapter.out.persistence;

import java.util.List;
import java.util.Optional;

import cv.sitech.adapter.out.entity.JournalEntryEntity;
import cv.sitech.adapter.out.persistence.mapper.JournalEntryMapper;
import cv.sitech.application.port.out.JournalEntryRepository;
import cv.sitech.domain.ledger.JournalEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class JournalEntryPersistenceAdapter extends PanacheAdapter<JournalEntry, JournalEntryEntity, Long>
    implements JournalEntryRepository {

  protected JournalEntryPersistenceAdapter() {
    super(null, null);
  }

  @Inject
  public JournalEntryPersistenceAdapter(JournalEntryPanacheRepository panache, JournalEntryMapper mapper) {
    super(panache, mapper);
  }

  @Override
  public Optional<JournalEntry> findByReference(String reference) {
    return first("reference", reference);
  }

  @Override
  public List<JournalEntry> findByAccount(Long accountId) {
    return list("select distinct e from JournalEntryEntity e join e.postings p where p.accountId = ?1 order by e.id",
        accountId);
  }

}
