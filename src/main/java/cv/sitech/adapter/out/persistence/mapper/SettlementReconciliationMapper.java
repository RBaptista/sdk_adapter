package cv.sitech.adapter.out.persistence.mapper;

import org.mapstruct.Mapper;

import cv.sitech.adapter.out.entity.SettlementReconciliationEntity;
import cv.sitech.domain.settlement.SettlementReconciliation;

@Mapper
public interface SettlementReconciliationMapper extends EntityMapper<SettlementReconciliation, SettlementReconciliationEntity> {

}
