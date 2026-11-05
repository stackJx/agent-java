-- agentcore 表结构（SQLite 3，单机 / 嵌入式部署）。

-- table: conversation
CREATE TABLE ${prefix}conversation (
    id                 TEXT     NOT NULL PRIMARY KEY,
    title              TEXT,
    metadata           TEXT,
    message_count      INTEGER  NOT NULL,
    last_seq           INTEGER  NOT NULL,
    input_tokens       INTEGER  NOT NULL,
    output_tokens      INTEGER  NOT NULL,
    cache_read_tokens  INTEGER  NOT NULL,
    cache_write_tokens INTEGER  NOT NULL,
    requests           INTEGER  NOT NULL,
    created_at         DATETIME NOT NULL,
    updated_at         DATETIME NOT NULL
);
CREATE INDEX ${prefix}conv_updated_idx ON ${prefix}conversation (updated_at);

-- table: conversation_event
CREATE TABLE ${prefix}conversation_event (
    conversation_id TEXT     NOT NULL,
    seq             INTEGER  NOT NULL,
    event_type      TEXT     NOT NULL,
    payload         TEXT     NOT NULL,
    created_at      DATETIME NOT NULL,
    PRIMARY KEY (conversation_id, seq)
);

-- table: memory
CREATE TABLE ${prefix}memory (
    scope_id    TEXT     NOT NULL,
    name        TEXT     NOT NULL,
    description TEXT,
    memory_type TEXT     NOT NULL,
    content     TEXT,
    updated_at  DATETIME NOT NULL,
    PRIMARY KEY (scope_id, name)
);
