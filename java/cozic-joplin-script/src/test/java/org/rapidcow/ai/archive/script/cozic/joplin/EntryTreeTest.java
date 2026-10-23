package org.rapidcow.ai.archive.script.cozic.joplin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinEntry;
import org.rapidcow.ai.archive.bridge.cozic.joplin.JoplinType;

class EntryTreeTest {
    /** Only what {@link EntryTree} looks at; parent links are set by {@link #link}. */
    private static final class Fake implements JoplinEntry {
        private final String id;
        private final JoplinType type;
        private final String title;
        private Fake parent;
        private final List<JoplinEntry> children = new ArrayList<>();
        private final Map<String, String> metadata = new HashMap<>();

        Fake(String id, JoplinType type, String title) {
            this.id = id;
            this.type = type;
            this.title = title;
        }

        @Override public String getId() { return id; }
        @Override public JoplinType getType() { return type; }
        @Override public String getTitle() { return title; }
        @Override public Optional<JoplinEntry> getParent() { return Optional.ofNullable(parent); }
        @Override public List<JoplinEntry> getChildren() { return children; }
        @Override public Map<String, String> getMetadata() { return metadata; }
        @Override public String getContent() { throw new UnsupportedOperationException(); }
        @Override public long getContentSize() { throw new UnsupportedOperationException(); }
        @Override public InputStream openContentStream() { throw new UnsupportedOperationException(); }
        @Override public InputStream openResourceStream() { throw new UnsupportedOperationException(); }
        @Override public long getResourceSize() { throw new UnsupportedOperationException(); }
    }

    private static Fake folder(String id, String title) { return new Fake(id, JoplinType.FOLDER, title); }
    private static Fake note(String id, String title) { return new Fake(id, JoplinType.NOTE, title); }

    private static void link(Fake parent, Fake child) {
        child.parent = parent;
        parent.children.add(child);
    }

    /** One line per node, indented by depth; "title (id)", with " *" on cycle leaves. */
    private static String dump(Fake... entries) {
        return dump(EntryTree.Order.TITLE, entries);
    }

    private static String dump(EntryTree.Order order, Fake... entries) {
        var out = new StringBuilder();
        dump(EntryTree.of(List.of(entries), order), -1, out);
        return out.toString();
    }

    private static void dump(DefaultMutableTreeNode node, int depth, StringBuilder out) {
        if (node.getUserObject() instanceof EntryTree.Item item) {
            out.append("  ".repeat(depth)).append(item.entry().getTitle())
                    .append(" (").append(item.entry().getId()).append(")")
                    .append(item.cycle() ? " *" : "").append('\n');
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            dump((DefaultMutableTreeNode) node.getChildAt(i), depth + 1, out);
        }
    }

    @Test
    void sortsByTitleThenIdAndLetsNotesHaveChildren() {
        var work = folder("f1", "Work");
        var diary = folder("f2", "diary");
        var b = note("n2", "Same");
        var a = note("n1", "Same");
        var untitled = note("n3", "");
        var reply = note("n4", "Reply");
        var subfolder = folder("f3", "Under a note");
        link(work, b);
        link(work, a);
        link(work, untitled);
        link(a, reply);
        link(a, subfolder);
        assertEquals("""
                diary (f2)
                Work (f1)
                   (n3)
                  Same (n1)
                    Reply (n4)
                    Under a note (f3)
                  Same (n2)
                """, dump(work, diary, b, a, untitled, reply, subfolder));
    }

    @Test
    void sortsByEitherTimeNewestFirstWithMissingTimesLast() {
        var folder = folder("f", "Folder");
        var old = note("n1", "Old");
        var recent = note("n2", "Recent");
        var tie = note("n3", "A tie with Recent");
        var untimed = note("n4", "0 has no time");
        var garbled = note("n5", "1 has an invalid time");
        for (Fake n : List.of(old, recent, tie, untimed, garbled)) {
            link(folder, n);
        }
        old.metadata.put("updated_time", "2024-04-02T10:00:00.000Z");
        recent.metadata.put("updated_time", "2024-09-22T10:00:00.000Z");
        tie.metadata.put("updated_time", "2024-09-22T10:00:00.000Z");
        garbled.metadata.put("updated_time", "yesterday");
        // The user's own edit times disagree with the other ones.
        old.metadata.put("user_updated_time", "2025-01-01T00:00:00.000Z");
        recent.metadata.put("user_updated_time", "2023-01-01T00:00:00.000Z");
        Fake[] all = { folder, old, recent, tie, untimed, garbled };
        assertEquals("""
                Folder (f)
                  A tie with Recent (n3)
                  Recent (n2)
                  Old (n1)
                  0 has no time (n4)
                  1 has an invalid time (n5)
                """, dump(EntryTree.Order.MODIFICATION_TIME, all));
        assertEquals("""
                Folder (f)
                  Old (n1)
                  Recent (n2)
                  0 has no time (n4)
                  1 has an invalid time (n5)
                  A tie with Recent (n3)
                """, dump(EntryTree.Order.USER_MODIFICATION_TIME, all));
    }

    @Test
    void leavesOutOtherTypesAndRootsTheirChildren() {
        var tag = new Fake("t", JoplinType.TAG, "A tag");
        var resource = new Fake("r", JoplinType.RESOURCE, "A resource");
        var underTag = note("n1", "Under a tag");
        var orphan = note("n2", "Orphan");
        link(tag, underTag);
        // A parent missing from the export: getParent() is empty.
        assertEquals("""
                Orphan (n2)
                Under a tag (n1)
                """, dump(tag, resource, underTag, orphan));
    }

    @Test
    void breaksCycleAtFirstMemberAndKeepsItsBranches() {
        var a = folder("a", "A");
        var b = note("b", "B");
        var c = folder("c", "C");
        var tail = note("t", "0 sorts first, but is not in the cycle");
        var aside = note("x", "Aside");
        link(c, a);
        link(a, b);
        link(b, c);
        link(tail, aside);
        link(b, tail);
        assertEquals("""
                A (a)
                  B (b)
                    0 sorts first, but is not in the cycle (t)
                      Aside (x)
                    C (c)
                      A (a) *
                """, dump(tail, aside, c, b, a));
    }

    @Test
    void handlesSelfLoopAndSeparateCycles() {
        var self = folder("s", "Self");
        link(self, self);
        var p = note("p", "P");
        var q = note("q", "Q");
        link(p, q);
        link(q, p);
        assertEquals("""
                P (p)
                  Q (q)
                    P (p) *
                Self (s)
                  Self (s) *
                """, dump(q, self, p));
    }
}
