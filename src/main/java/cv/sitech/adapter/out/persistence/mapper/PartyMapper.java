package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.PartyEntity;
import cv.sitech.domain.Party;

@Mapper(uses = AccountMapper.class)
public interface PartyMapper extends EntityMapper<Party, PartyEntity> {

}
