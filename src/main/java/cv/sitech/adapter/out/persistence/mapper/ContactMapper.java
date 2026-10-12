package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.ContactEntity;
import cv.sitech.domain.Contact;

@Mapper
public interface ContactMapper extends EntityMapper<Contact, ContactEntity> {

}
