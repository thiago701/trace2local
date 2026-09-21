package tech.neural7.trace2local.internal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import tech.neural7.trace2local.model.FieldDelta;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Calcula os deltas de campo entre {@code before} e {@code after} (SPEC §4.6). */
public final class DeltaCalculator {

    private DeltaCalculator() {}

    public static List<FieldDelta> compute(JsonNode before, JsonNode after) {
        List<FieldDelta> deltas = new ArrayList<>();
        diff("", before, after, deltas);
        return List.copyOf(deltas);
    }

    private static void diff(String path, JsonNode before, JsonNode after, List<FieldDelta> out) {
        if (before == null) {
            addAll(path, after, out, true);
            return;
        }
        if (after == null) {
            addAll(path, before, out, false);
            return;
        }
        JsonNode b = before.isNull() ? NullNode.instance : before;
        JsonNode a = after.isNull() ? NullNode.instance : after;
        if (b.isObject() && a.isObject()) {
            var keys = new java.util.LinkedHashSet<String>();
            b.fieldNames().forEachRemaining(keys::add);
            a.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                JsonNode bv = b.get(key);
                JsonNode av = a.get(key);
                if (bv == null) bv = NullNode.instance;
                if (av == null) av = NullNode.instance;
                String childPath = path.isEmpty() ? key : path + "." + key;
                if (bv.isContainerNode() || av.isContainerNode()) {
                    if (!bv.equals(av)) {
                        diff(childPath, bv.isNull() ? null : bv, av.isNull() ? null : av, out);
                    }
                } else if (!bv.equals(av)) {
                    out.add(new FieldDelta(childPath,
                            bv.isNull() ? NullNode.instance : bv,
                            av.isNull() ? NullNode.instance : av));
                }
            }
        } else if (b.isArray() && a.isArray()) {
            if (!b.equals(a)) {
                out.add(new FieldDelta(path, b, a));
            }
        } else if (!b.equals(a)) {
            out.add(new FieldDelta(path.isEmpty() ? "$" : path, b, a));
        }
    }

    private static void addAll(String path, JsonNode node, List<FieldDelta> out, boolean added) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            var it = node.fields();
            while (it.hasNext()) {
                var e = it.next();
                String childPath = path.isEmpty() ? e.getKey() : path + "." + e.getKey();
                if (e.getValue().isContainerNode()) {
                    addAll(childPath, e.getValue(), out, added);
                } else {
                    out.add(added
                            ? new FieldDelta(childPath, NullNode.instance, e.getValue())
                            : new FieldDelta(childPath, e.getValue(), NullNode.instance));
                }
            }
        } else {
            out.add(added
                    ? new FieldDelta(path.isEmpty() ? "$" : path, NullNode.instance, node)
                    : new FieldDelta(path.isEmpty() ? "$" : path, node, NullNode.instance));
        }
    }
}
