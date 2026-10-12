package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.DisbursementEntity;
import cv.sitech.domain.disbursement.Disbursement;

@Mapper
public interface DisbursementMapper extends EntityMapper<Disbursement, DisbursementEntity> {

}
