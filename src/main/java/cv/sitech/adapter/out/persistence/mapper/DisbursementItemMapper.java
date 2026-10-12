package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.DisbursementItemEntity;
import cv.sitech.domain.disbursement.DisbursementItem;

@Mapper
public interface DisbursementItemMapper extends EntityMapper<DisbursementItem, DisbursementItemEntity> {

}
