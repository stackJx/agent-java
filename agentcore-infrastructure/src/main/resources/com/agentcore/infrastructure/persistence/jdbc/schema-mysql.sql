-- agentcore 表结构（MySQL 5.7+ / MariaDB / OceanBase MySQL 模式 / TiDB）。

-- table: conversation
CREATE TABLE ${prefix}conversation (
    id                 VARCHAR(128)  NOT NULL PRIMARY KEY,
    title              VARCHAR(255),
    metadata           LONGTEXT,
    message_count      INT           NOT NULL,
    last_seq           BIGINT        NOT NULL,
    input_tokens       BIGINT        NOT NULL,
    output_tokens      BIGINT        NOT NULL,
    cache_read_tokens  BIGINT        NOT NULL,
    cache_write_tokens BIGINT        NOT NULL,
    requests           INT           NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE INDEX ${prefix}conv_updated_idx ON ${prefix}conversation (updated_at);

-- table: conversation_event
CREATE TABLE ${prefix}conversation_event (
    conversation_id VARCHAR(128) NOT NULL,
    seq             BIGINT       NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    payload         LONGTEXT     NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (conversation_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- table: memory
CREATE TABLE ${prefix}memory (
    scope_id    VARCHAR(128)  NOT NULL,
    name        VARCHAR(128)  NOT NULL,
    description VARCHAR(1000),
    memory_type VARCHAR(32)   NOT NULL,
    content     LONGTEXT,
    updated_at  DATETIME(6)   NOT NULL,
    PRIMARY KEY (scope_id, name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
