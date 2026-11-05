-- agentcore 表结构（SQL Server 2016+ / Azure SQL）。

-- table: conversation
CREATE TABLE ${prefix}conversation (
    id                 NVARCHAR(128)  NOT NULL PRIMARY KEY,
    title              NVARCHAR(255),
    metadata           NVARCHAR(MAX),
    message_count      INT            NOT NULL,
    last_seq           BIGINT         NOT NULL,
    input_tokens       BIGINT         NOT NULL,
    output_tokens      BIGINT         NOT NULL,
    cache_read_tokens  BIGINT         NOT NULL,
    cache_write_tokens BIGINT         NOT NULL,
    requests           INT            NOT NULL,
    created_at         DATETIME2      NOT NULL,
    updated_at         DATETIME2      NOT NULL
);
CREATE INDEX ${prefix}conv_updated_idx ON ${prefix}conversation (updated_at);

-- table: conversation_event
CREATE TABLE ${prefix}conversation_event (
    conversation_id NVARCHAR(128) NOT NULL,
    seq             BIGINT        NOT NULL,
    event_type      NVARCHAR(32)  NOT NULL,
    payload         NVARCHAR(MAX) NOT NULL,
    created_at      DATETIME2     NOT NULL,
    PRIMARY KEY (conversation_id, seq)
);

-- table: memory
CREATE TABLE ${prefix}memory (
    scope_id    NVARCHAR(128)  NOT NULL,
    name        NVARCHAR(128)  NOT NULL,
    description NVARCHAR(1000),
    memory_type NVARCHAR(32)   NOT NULL,
    content     NVARCHAR(MAX),
    updated_at  DATETIME2      NOT NULL,
    PRIMARY KEY (scope_id, name)
);
