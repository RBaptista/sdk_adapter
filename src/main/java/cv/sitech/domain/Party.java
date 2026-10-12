package cv.sitech.domain;

public class Party {
  private Long id;
  private IdTypeEnum idType;
  private String idValue;
  private Account account;

  public Party(IdTypeEnum idType, String idValue, Account account) {
    this.idType = idType;
    this.idValue = idValue;
    this.account = account;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public IdTypeEnum getIdType() {
    return idType;
  }

  public void setIdType(IdTypeEnum idType) {
    this.idType = idType;
  }

  public String getIdValue() {
    return idValue;
  }

  public void setIdValue(String idValue) {
    this.idValue = idValue;
  }

  public Account getAccount() {
    return account;
  }

  public void setAccount(Account account) {
    this.account = account;
  }

}
