-- accounts become ledger accounts: customer wallets plus per-DFSP position and settlement accounts.
-- balances are owned by the ledger (journal_entries/postings) and cached here.
alter table accounts alter column reserved_balance type numeric(18, 4);
update accounts set balance = 0 where balance is null;
update accounts set reserved_balance = 0 where reserved_balance is null;
alter table accounts
    alter column balance set default 0,
    alter column balance set not null,
    alter column reserved_balance set default 0,
    alter column reserved_balance set not null;
alter table accounts add column pending_credit numeric(18, 4) not null default 0;
alter table accounts add column type varchar(16) not null default 'CUSTOMER';

-- customers belong to one DFSP
alter table customers add column dfsp_id varchar(32);
alter table customers add constraint fk_customers_dfsp foreign key (dfsp_id) references dfsp (fsp_id);
create index idx_customers_dfsp_id on customers (dfsp_id);

-- parties are generic FSPIOP identifiers (MSISDN, ACCOUNT_ID, ...)
alter table parties rename column msisdn to id_value;
alter table parties drop constraint uk_parties_msisdn;
update parties set id_type = 'MSISDN' where id_type is null;
alter table parties alter column id_type set not null;
alter table parties add constraint uk_parties_id unique (id_type, id_value);

create sequence journal_entries_seq start with 1 increment by 50;
create sequence postings_seq start with 1 increment by 50;
create sequence transfers_seq start with 1 increment by 50;
create sequence settlement_reconciliations_seq start with 1 increment by 50;

create table journal_entries (
    id         bigint       not null,
    reference  varchar(128) not null,
    type       varchar(16)  not null,
    status     varchar(16)  not null,
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    constraint pk_journal_entries primary key (id),
    constraint uk_journal_entries_reference unique (reference)
);

create table postings (
    id         bigint         not null,
    entry_id   bigint         not null,
    account_id bigint         not null,
    amount     numeric(18, 4) not null,
    constraint pk_postings primary key (id),
    constraint fk_postings_entry foreign key (entry_id) references journal_entries (id),
    constraint fk_postings_account foreign key (account_id) references accounts (id)
);
create index idx_postings_entry_id on postings (entry_id);
create index idx_postings_account_id on postings (account_id);

-- one row per side of a transfer this app takes part in (OUTBOUND = payer, INBOUND = payee);
-- an INBOUND row is created by the quote request, before the transfer id is known
create table transfers (
    id                   bigint         not null,
    direction            varchar(8)     not null,
    transfer_id          varchar(36),
    quote_id             varchar(36),
    transaction_id       varchar(36),
    payer_fsp_id         varchar(32),
    payer_id_type        varchar(16),
    payer_id_value       varchar(128),
    payer_account_id     bigint,
    payee_fsp_id         varchar(32),
    payee_id_type        varchar(16),
    payee_id_value       varchar(128),
    payee_name           varchar(255),
    payee_account_id     bigint,
    amount               numeric(18, 4) not null,
    currency             varchar(3)     not null,
    transfer_amount      numeric(18, 4),
    payee_receive_amount numeric(18, 4),
    payee_fee            numeric(18, 4),
    ilp_packet           text,
    ilp_condition        varchar(64),
    fulfilment           varchar(64),
    expiration           timestamptz,
    state                varchar(32)    not null,
    error_code           varchar(8),
    error_description    varchar(512),
    auto_accept          boolean        not null default false,
    note                 varchar(255),
    created_at           timestamptz    not null,
    updated_at           timestamptz    not null,
    completed_at         timestamptz,
    constraint pk_transfers primary key (id),
    constraint uk_transfers_transfer_id unique (direction, transfer_id),
    constraint uk_transfers_quote_id unique (direction, quote_id),
    constraint fk_transfers_payer_account foreign key (payer_account_id) references accounts (id),
    constraint fk_transfers_payee_account foreign key (payee_account_id) references accounts (id)
);
create index idx_transfers_payer_fsp on transfers (direction, payer_fsp_id);
create index idx_transfers_payee_fsp on transfers (direction, payee_fsp_id);
create index idx_transfers_state on transfers (state);
create index idx_transfers_condition on transfers (ilp_condition);

create table settlement_reconciliations (
    id                bigint         not null,
    kind              varchar(16)    not null,
    hub_reference     varchar(64)    not null,
    fsp_id            varchar(32)    not null,
    currency          varchar(3)     not null,
    hub_amount        numeric(18, 4) not null,
    internal_amount   numeric(18, 4) not null,
    status            varchar(16)    not null,
    hub_state         varchar(32),
    posted_to_ledger  boolean        not null default false,
    reconciled_at     timestamptz    not null,
    constraint pk_settlement_reconciliations primary key (id),
    constraint fk_settlement_reconciliations_dfsp foreign key (fsp_id) references dfsp (fsp_id)
);
create index idx_settlement_reconciliations_fsp on settlement_reconciliations (fsp_id, kind);
