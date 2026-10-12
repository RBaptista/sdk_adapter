package cv.sitech.adapter.in.rest;

import cv.sitech.adapter.in.rest.dto.DfspDtos.AccountView;
import cv.sitech.adapter.in.rest.dto.DfspDtos.DfspStatus;
import cv.sitech.adapter.in.rest.dto.DfspDtos.DfspView;
import cv.sitech.application.port.in.AccountUseCase;
import cv.sitech.application.port.in.DfspUseCase;
import cv.sitech.domain.AccountTypeEnum;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

/** The logged-in DFSP. */
@Path("/api/dfsp")
public class DfspResource {

  private final CurrentDfsp current;
  private final DfspUseCase dfspUseCase;
  private final AccountUseCase accountUseCase;

  public DfspResource(CurrentDfsp current, DfspUseCase dfspUseCase, AccountUseCase accountUseCase) {
    this.current = current;
    this.dfspUseCase = dfspUseCase;
    this.accountUseCase = accountUseCase;
  }

  @GET
  public DfspStatus get() {
    String fspId = current.fspId();
    var accounts = accountUseCase.findAll(fspId);
    return new DfspStatus(DfspView.from(dfspUseCase.findById(fspId)),
        accounts.stream().filter(a -> a.getType() == AccountTypeEnum.POSITION).findFirst().map(AccountView::from)
            .orElse(null),
        accounts.stream().filter(a -> a.getType() == AccountTypeEnum.SETTLEMENT).findFirst().map(AccountView::from)
            .orElse(null));
  }

}
