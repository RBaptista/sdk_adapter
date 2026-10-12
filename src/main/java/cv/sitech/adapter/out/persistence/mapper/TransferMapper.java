package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.TransferEntity;
import cv.sitech.domain.transfer.Transfer;

@Mapper
public interface TransferMapper extends EntityMapper<Transfer, TransferEntity> {

}
