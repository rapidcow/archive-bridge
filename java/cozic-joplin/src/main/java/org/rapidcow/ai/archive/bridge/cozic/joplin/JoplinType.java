package org.rapidcow.ai.archive.bridge.cozic.joplin;

/**
 * Joplin ModelType values (including internal models not normally exported).
 * @see <a href="https://github.com/laurent22/joplin/blob/dev/packages/lib/BaseModel.ts">Joplin BaseModel</a>
 */
public enum JoplinType {
    NOTE(1),
    FOLDER(2),
    SETTING(3),
    RESOURCE(4),
    TAG(5),
    NOTE_TAG(6),
    SEARCH(7),
    ALARM(8),
    MASTER_KEY(9),
    ITEM_CHANGE(10),
    NOTE_RESOURCE(11),
    RESOURCE_LOCAL_STATE(12),
    REVISION(13),
    MIGRATION(14),
    SMART_FILTER(15),
    COMMAND(16),
    NOTE_EMBEDDING(17),
    CONFLICT_NOTE_STATE(18),
    CHAT_CONVERSATION(19),
    CHAT_MESSAGE(20);

    private final int value;

    JoplinType(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static JoplinType fromValue(int value) {
        for (var type : values()) {
            if (type.value == value) return type;
        }
        throw new IllegalArgumentException("Unknown Joplin item type");
    }
}
