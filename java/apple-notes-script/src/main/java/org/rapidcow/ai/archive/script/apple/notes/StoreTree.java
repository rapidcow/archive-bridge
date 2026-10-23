package org.rapidcow.ai.archive.script.apple.notes;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.tree.DefaultMutableTreeNode;
import org.rapidcow.ai.archive.bridge.apple.notes.store.NoteStore;
import org.rapidcow.ai.archive.bridge.apple.notes.store.NoteStoreRow;

/** A lightweight index: blobs and row details are only read on selection. */
final class StoreTree implements AutoCloseable {
    record Item(NoteStoreRow row, String title) {
        @Override public String toString() { return title + " [" + row.pk() + "]"; }
    }
    final Path path;
    private final NoteStore store;
    final Map<String, List<Item>> entities = new LinkedHashMap<>();
    final java.util.Set<String> unavailable = new java.util.HashSet<>();
    int count;

    private StoreTree(Path path, NoteStore store) {
        this.path = path;
        this.store = store;
    }

    static StoreTree open(Path path) {
        var result = new StoreTree(path.toAbsolutePath(), NoteStore.open(path));
        try {
            for (var entity : result.store.entities()) {
                result.entities.put(entity.name(), new ArrayList<>());
            }
            // Root queries include descendants; querying each root once avoids duplicates.
            for (var entity : result.store.entities()) {
                if (entity.superId() != 0) continue;
                final java.util.stream.Stream<NoteStoreRow> stream;
                try {
                    stream = result.store.rows(entity.name());
                } catch (IllegalArgumentException e) {
                    // Core Data history entities can be declared without a normal Z table.
                    result.unavailable.add(entity.name());
                    continue;
                }
                try (var rows = stream) {
                    rows.forEach(row -> {
                        String title = row.entity().name();
                        for (String column : List.of("ZTITLE1", "ZTITLE2", "ZTITLE", "ZNAME", "ZIDENTIFIER")) {
                            if (row.columns().contains(column) && row.get(column) instanceof String s && !s.isBlank()) {
                                title = s;
                                break;
                            }
                        }
                        result.entities.get(row.entity().name()).add(new Item(row, title));
                        result.count++;
                    });
                }
            }
            return result;
        } catch (RuntimeException e) {
            result.close();
            throw e;
        }
    }

    DefaultMutableTreeNode root(boolean byTitle) {
        var root = new DefaultMutableTreeNode();
        entities.forEach((name, items) -> {
            var group = new DefaultMutableTreeNode(name + (unavailable.contains(name) ? " (no table)" : " (" + items.size() + ")"));
            root.add(group);
            Comparator<Item> order = Comparator.comparingLong(item -> item.row().pk());
            if (byTitle) order = Comparator.comparing(Item::title, String.CASE_INSENSITIVE_ORDER).thenComparing(order);
            items.stream().sorted(order).forEach(item -> group.add(new DefaultMutableTreeNode(item, false)));
        });
        return root;
    }

    synchronized Map<String, String> details(Item item) {
        var properties = new LinkedHashMap<String, String>();
        properties.put("Entity", item.row().entity().name());
        for (String column : item.row().columns()) {
            Object value = item.row().get(column);
            if (value != null) properties.put(column,
                    value instanceof byte[] bytes ? "<" + bytes.length + " bytes>" : value.toString());
        }
        return properties;
    }

    synchronized boolean isDrawing(Item item) {
        var entity = item.row().entity();
        var entities = store.entities();
        while (!entity.name().equals("ICAttachment") && entity.superId() != 0) {
            int parentId = entity.superId();
            entity = entities.stream().filter(e -> e.id() == parentId).findFirst().orElseThrow();
        }
        if (!entity.name().equals("ICAttachment")) return false;
        var columns = item.row().columns();
        if (!columns.contains("ZMERGEABLEDATA1") || !(item.row().get("ZMERGEABLEDATA1") instanceof byte[] bytes)
                || bytes.length == 0) return false;
        if (!columns.contains("ZTYPEUTI") || !"com.apple.drawing.2".equals(item.row().get("ZTYPEUTI"))) {
            throw new IllegalArgumentException("This attachment is not a supported com.apple.drawing.2 drawing");
        }
        return true;
    }

    @Override public synchronized void close() { store.close(); }
}
