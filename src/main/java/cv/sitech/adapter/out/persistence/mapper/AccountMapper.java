package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.AccountEntity;
import cv.sitech.domain.Account;

@Mapper(uses = { CustomerMapper.class, DfspMapper.class })
public interface AccountMapper extends EntityMapper<Account, AccountEntity> {

}
