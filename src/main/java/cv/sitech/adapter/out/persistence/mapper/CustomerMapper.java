package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.CustomerEntity;
import cv.sitech.domain.Customer;

@Mapper(uses = ContactMapper.class)
public interface CustomerMapper extends EntityMapper<Customer, CustomerEntity> {

}
