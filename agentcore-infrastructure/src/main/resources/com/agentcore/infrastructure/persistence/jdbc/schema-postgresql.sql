-- agentcore 表结构（PostgreSQL 10+ / 人大金仓 KingbaseES / openGauss / GaussDB / CockroachDB）。

-- table: conversation
CREATE TABLE ${prefix}conversation (
    id                 VARCHAR(128)  NOT NULL PRIMARY KEY,
    title              VARCHAR(255),
    metadata           TEXT,
    message_count      INTEGER       NOT NULL,
    last_seq           BIGINT        NOT NULL,
    input_tokens       BIGINT        NOT NULL,
    output_tokens      BIGINT        NOT NULL,
    cache_read_tokens  BIGINT        NOT NULL,
    cache_write_tokens BIGINT        NOT NULL,
    requests           INTEGER       NOT NULL,
    created_at         TIMESTAMP     NOT NULL,
    updated_at         TIMESTAMP     NOT NULL
);
CREATE INDEX ${prefix}conv_updated_idx ON ${prefix}conversation (updated_at);

-- table: conversation_event
CREATE TABLE ${prefix}conversation_event (
    conversation_id VARCHAR(128) NOT NULL,
    seq             BIGINT       NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    payload         TEXT         NOT NULL,
    created_at      TIMESTAMP    NOT NULL,
    PRIMARY KEY (conversation_id, seq)
);

-- table: memory
CREATE TABLE ${prefix}memory (
    scope_id    VARCHAR(128)  NOT NULL,
    name        VARCHAR(128)  NOT NULL,
    description VARCHAR(1000),
    memory_type VARCHAR(32)   NOT NULL,
    content     TEXT,
    updated_at  TIMESTAMP     NOT NULL,
    PRIMARY KEY (scope_id, name)
);
