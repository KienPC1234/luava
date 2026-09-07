package org.luava.midend.optimizer;

import java.util.HashMap;
import java.util.Map;

public final class SlotAllocator {
    public enum SlotKind {
        PRIMITIVE_LONG,
        PRIMITIVE_DOUBLE,
        OBJECT
    }

    public record Slot(int jvmLocalIndex, SlotKind kind) {}

    private final Map<String, Slot> variableSlots = new HashMap<>();
    private int nextObjectSlot = 1;      // Slot 0 usually reserved for 'this'
    private int nextPrimitiveSlot = 100; // Distinct range for unboxed primitives

    public Slot allocate(String variableName, SlotKind kind) {
        Slot slot;
        if (kind == SlotKind.PRIMITIVE_LONG || kind == SlotKind.PRIMITIVE_DOUBLE) {
            slot = new Slot(nextPrimitiveSlot, kind);
            nextPrimitiveSlot += 2; // longs and doubles take 2 slots in JVM
        } else {
            slot = new Slot(nextObjectSlot++, SlotKind.OBJECT);
        }
        variableSlots.put(variableName, slot);
        return slot;
    }

    public Slot getSlot(String variableName) {
        return variableSlots.get(variableName);
    }

    public int totalSlotsRequired() {
        return nextPrimitiveSlot;
    }
}
