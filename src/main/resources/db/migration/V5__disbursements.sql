-- G2P disbursements: a batch paid by one party of a hosted DFSP, each item executed as its own
-- OUTBOUND transfer (the harness has no bulk-api-adapter, so there is no FSPIOP bulk transfer)
create sequence disbursements_seq start with 1 increment by 50;
create sequence disbursement_items_seq start with 1 increment by 50;

create table disbursements (
    id               bigint         not null,
    disbursement_id  varchar(36)    not null,
    payer_fsp_id     varchar(32)    not null,
    payer_id_type    varchar(16)    not null,
    payer_id_value   varchar(128)   not null,
    payer_account_id bigint         not null,
    currency         varchar(3)     not null,
    reference        varchar(128),
    note             varchar(255),
    state            varchar(32)    not null,
    item_count       integer        not null default 0,
    total_amount     numeric(18, 4) not null default 0,
    committed_count  integer        not null default 0,
    committed_amount numeric(18, 4) not null default 0,
    failed_count     integer        not null default 0,
    created_at       timestamptz    not null,
    updated_at       timestamptz    not null,
    approved_at      timestamptz,
    completed_at     timestamptz,
    constraint pk_disbursements primary key (id),
    constraint uk_disbursements_disbursement_id unique (disbursement_id),
    constraint fk_disbursements_dfsp foreign key (payer_fsp_id) references dfsp (fsp_id),
    constraint fk_disbursements_payer_account foreign key (payer_account_id) references accounts (id)
);
create index idx_disbursements_payer_fsp on disbursements (payer_fsp_id);
create index idx_disbursements_state on disbursements (state);

-- linked to its transfer by home_transaction_id, which is generated with the item and never changes
-- (transfer_id is replaced by the SDK's id after discovery), so there is no foreign key to transfers
create table disbursement_items (
    id                  bigint         not null,
    disbursement_id     varchar(36)    not null,
    line_no             integer        not null,
    payee_id_type       varchar(16)    not null,
    payee_id_value      varchar(128)   not null,
    amount              numeric(18, 4) not null,
    note                varchar(255),
    state               varchar(32)    not null,
    home_transaction_id varchar(36)    not null,
    transfer_id         varchar(36),
    payee_fsp_id        varchar(32),
    payee_name          varchar(255),
    error_code          varchar(8),
    error_description   varchar(512),
    created_at          timestamptz    not null,
    updated_at          timestamptz    not null,
    constraint pk_disbursement_items primary key (id),
    constraint uk_disbursement_items_line unique (disbursement_id, line_no),
    constraint uk_disbursement_items_home_transaction_id unique (home_transaction_id),
    constraint fk_disbursement_items_disbursement foreign key (disbursement_id)
        references disbursements (disbursement_id)
);
-- the worker claims QUEUED items and the sync job scans SUBMITTED ones
create index idx_disbursement_items_state on disbursement_items (state);
