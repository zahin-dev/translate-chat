CREATE TABLE IF NOT EXISTS chat_rooms (
    id UUID PRIMARY KEY,
    invite_code VARCHAR(12) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS chat_participants (
    id UUID PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES chat_rooms(id),
    display_name VARCHAR(120) NOT NULL,
    language VARCHAR(2) NOT NULL CHECK (language IN ('ja', 'en')),
    token_hash CHAR(64) NOT NULL UNIQUE,
    last_seen TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    left_at TIMESTAMPTZ
);
CREATE TABLE IF NOT EXISTS chat_messages (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    room_id UUID NOT NULL REFERENCES chat_rooms(id),
    participant_id UUID NOT NULL REFERENCES chat_participants(id),
    client_id UUID NOT NULL,
    original_text TEXT NOT NULL,
    source_language VARCHAR(3) NOT NULL CHECK (source_language IN ('ja', 'en', 'und')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (room_id, participant_id, client_id)
);
CREATE INDEX IF NOT EXISTS chat_messages_room_order ON chat_messages(room_id, id);
CREATE INDEX IF NOT EXISTS chat_participants_presence ON chat_participants(room_id, last_seen) WHERE left_at IS NULL;
