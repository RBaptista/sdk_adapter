package cv.sitech.domain.transfer;

import cv.sitech.domain.IdTypeEnum;

/** A party as returned by FSPIOP discovery. */
public record PartyInfo(IdTypeEnum idType, String idValue, String fspId, String name) {
}
