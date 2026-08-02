package com.icysnex.ghosttap.core.input;

import org.lwjgl.input.Keyboard;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

// Synthetic keyboard state consumed by the LWJGL Keyboard mixin. Keeping both
// polled state and buffered edges makes the key look physical to Minecraft and
// to HUD mods that read LWJGL directly.
public final class InputKeyboard {

    public static final byte STATE_UP = 0;
    public static final byte STATE_DOWN = 1;

    public static final class Event {
        public final int key;
        public final byte state;

        public Event(int key, byte state) {
            this.key = key;
            this.state = state;
        }
    }

    public static final ConcurrentLinkedQueue<Event> pendingEvents = new ConcurrentLinkedQueue<>();
    public static final AtomicBoolean pollSpaceLatch = new AtomicBoolean(false);

    // Published by the mixin after every hardware poll.
    public static volatile byte realSpace = STATE_UP;
    public static volatile byte spoofedSpace = STATE_UP;

    private InputKeyboard() {
    }

    public static synchronized void downSpace() {
        if (spoofedSpace == STATE_DOWN)
            return;

        spoofedSpace = STATE_DOWN;
        pollSpaceLatch.set(true);
        if (realSpace == STATE_UP)
            pendingEvents.add(new Event(Keyboard.KEY_SPACE, STATE_DOWN));
    }

    public static synchronized void upSpace() {
        if (spoofedSpace == STATE_UP)
            return;

        spoofedSpace = STATE_UP;
        if (realSpace == STATE_UP)
            pendingEvents.add(new Event(Keyboard.KEY_SPACE, STATE_UP));
    }

    public static boolean isSpaceDown() {
        return spoofedSpace == STATE_DOWN;
    }
}
