package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.JournalEntryEntity;
import cv.sitech.domain.ledger.JournalEntry;

@Mapper
public interface JournalEntryMapper extends EntityMapper<JournalEntry, JournalEntryEntity> {

}
