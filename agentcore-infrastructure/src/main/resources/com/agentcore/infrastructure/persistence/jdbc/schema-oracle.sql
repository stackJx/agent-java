-- agentcore 表结构（Oracle 12c+ / 达梦 DM8 / OceanBase Oracle 模式）。

-- table: conversation
CREATE TABLE ${prefix}conversation (
    id                 VARCHAR2(128 CHAR)  NOT NULL PRIMARY KEY,
    title              VARCHAR2(255 CHAR),
    metadata           CLOB,
    message_count      NUMBER(10)          NOT NULL,
    last_seq           NUMBER(19)          NOT NULL,
    input_tokens       NUMBER(19)          NOT NULL,
    output_tokens      NUMBER(19)          NOT NULL,
    cache_read_tokens  NUMBER(19)          NOT NULL,
    cache_write_tokens NUMBER(19)          NOT NULL,
    requests           NUMBER(10)          NOT NULL,
    created_at         TIMESTAMP           NOT NULL,
    updated_at         TIMESTAMP           NOT NULL
);
CREATE INDEX ${prefix}conv_updated_idx ON ${prefix}conversation (updated_at);

-- table: conversation_event
CREATE TABLE ${prefix}conversation_event (
    conversation_id VARCHAR2(128 CHAR) NOT NULL,
    seq             NUMBER(19)         NOT NULL,
    event_type      VARCHAR2(32 CHAR)  NOT NULL,
    payload         CLOB               NOT NULL,
    created_at      TIMESTAMP          NOT NULL,
    PRIMARY KEY (conversation_id, seq)
);

-- table: memory
CREATE TABLE ${prefix}memory (
    scope_id    VARCHAR2(128 CHAR)   NOT NULL,
    name        VARCHAR2(128 CHAR)   NOT NULL,
    description VARCHAR2(1000 CHAR),
    memory_type VARCHAR2(32 CHAR)    NOT NULL,
    content     CLOB,
    updated_at  TIMESTAMP            NOT NULL,
    PRIMARY KEY (scope_id, name)
);
