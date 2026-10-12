-- Hibernate's default @GeneratedValue for Long ids uses one sequence per table
-- named <table>_seq with an allocation size of 50
create sequence contacts_seq start with 1 increment by 50;
create sequence customers_seq start with 1 increment by 50;
create sequence accounts_seq start with 1 increment by 50;
create sequence parties_seq start with 1 increment by 50;

create table dfsp (
    fsp_id            varchar(32)    not null,
    display_name      varchar(255),
    currency          varchar(3)     not null,
    callback_base_url varchar(255)   not null,
    net_debit_cap     numeric(18, 4) not null,
    initial_position  numeric(18, 4) not null,
    initial_funds     numeric(18, 4) not null,
    constraint pk_dfsp primary key (fsp_id)
);

create table contacts (
    id        bigint       not null,
    phone     varchar(255),
    telephone varchar(255),
    email     varchar(255),
    constraint pk_contacts primary key (id)
);

create table customers (
    id         bigint       not null,
    name       varchar(255),
    contact_id bigint,
    address    varchar(255),
    nif        bigint,
    constraint pk_customers primary key (id),
    constraint fk_customers_contact foreign key (contact_id) references contacts (id)
);

create table accounts (
    id               bigint         not null,
    customer_id      bigint,
    balance          numeric(18, 4),
    reserved_balance real,
    dfsp_id          varchar(32),
    constraint pk_accounts primary key (id),
    constraint fk_accounts_customer foreign key (customer_id) references customers (id),
    constraint fk_accounts_dfsp foreign key (dfsp_id) references dfsp (fsp_id)
);

create table parties (
    id         bigint       not null,
    id_type    varchar(255),
    account_id bigint,
    msisdn     varchar(255) not null,
    constraint pk_parties primary key (id),
    constraint uk_parties_msisdn unique (msisdn),
    constraint fk_parties_account foreign key (account_id) references accounts (id)
);

create index idx_customers_contact_id on customers (contact_id);
create index idx_accounts_customer_id on accounts (customer_id);
create index idx_accounts_dfsp_id on accounts (dfsp_id);
create index idx_parties_account_id on parties (account_id);
