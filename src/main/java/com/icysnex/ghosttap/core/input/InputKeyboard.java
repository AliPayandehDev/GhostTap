package com.icysnex.ghosttap.core.input;

import org.lwjgl.input.Keyboard;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicIntegerArray;

// Synthetic keyboard state consumed by the LWJGL Keyboard mixin. Keeping both
// polled state and buffered edges makes the key look physical to Minecraft and
// to HUD mods that read LWJGL directly.
public final class InputKeyboard {

    public static final byte STATE_UP = 0;
    public static final byte STATE_DOWN = 1;
    public static final int STATE_PASSTHROUGH = -1;
    public static final int KEY_COUNT = 256;

    public static final class Event {
        public final int key;
        public final byte state;

        public Event(int key, byte state) {
            this.key = key;
            this.state = state;
        }
    }

    public static final ConcurrentLinkedQueue<Event> pendingEvents = new ConcurrentLinkedQueue<>();
    public static final AtomicIntegerArray realStates = new AtomicIntegerArray(KEY_COUNT);
    public static final AtomicIntegerArray overrideStates = new AtomicIntegerArray(KEY_COUNT);

    // Published by the mixin after every hardware poll.
    public static volatile byte realSpace = STATE_UP;
    public static volatile long pollSerial;

    static {
        for (int key = 0; key < KEY_COUNT; key++)
            overrideStates.set(key, STATE_PASSTHROUGH);
    }

    private InputKeyboard() {
    }

    public static void forceDown(int key) {
        setOverride(key, STATE_DOWN);
    }

    public static void forceUp(int key) {
        setOverride(key, STATE_UP);
    }

    public static void release(int key) {
        setOverride(key, STATE_PASSTHROUGH);
    }

    private static synchronized void setOverride(int key, int state) {
        if (!valid(key))
            return;

        int previousOverride = overrideStates.get(key);
        if (previousOverride == state)
            return;

        int real = realStates.get(key);
        int previousEffective = previousOverride == STATE_PASSTHROUGH ? real : previousOverride;
        int nextEffective = state == STATE_PASSTHROUGH ? real : state;
        overrideStates.set(key, state);
        if (previousEffective != nextEffective)
            pendingEvents.add(new Event(key, (byte) nextEffective));
    }

    public static byte real(int key) {
        return valid(key) ? (byte) realStates.get(key) : STATE_UP;
    }

    public static int override(int key) {
        return valid(key) ? overrideStates.get(key) : STATE_PASSTHROUGH;
    }

    public static void downSpace() {
        forceDown(Keyboard.KEY_SPACE);
    }

    public static void upSpace() {
        release(Keyboard.KEY_SPACE);
    }

    public static boolean isSpaceDown() {
        return override(Keyboard.KEY_SPACE) == STATE_DOWN;
    }

    private static boolean valid(int key) {
        return key >= 0 && key < KEY_COUNT;
    }
}
