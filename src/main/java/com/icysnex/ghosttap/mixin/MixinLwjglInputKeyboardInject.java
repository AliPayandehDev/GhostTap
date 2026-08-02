package com.icysnex.ghosttap.mixin;

import com.icysnex.ghosttap.core.input.InputKeyboard;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;

@Mixin(Keyboard.class)
public abstract class MixinLwjglInputKeyboardInject {

    @Shadow(remap = false)
    @Final
    private static ByteBuffer keyDownBuffer;

    @Shadow(remap = false)
    private static ByteBuffer readBuffer;

    @Inject(method = "poll", at = @At("HEAD"), remap = false)
    private static void beforePoll(CallbackInfo ci) {
        if (keyDownBuffer == null)
            return;

        int keys = Math.min(InputKeyboard.KEY_COUNT, keyDownBuffer.capacity());
        for (int key = 0; key < keys; key++)
            keyDownBuffer.put(key, (byte) InputKeyboard.realStates.get(key));
    }

    @Inject(method = "poll", at = @At("RETURN"), remap = false)
    private static void afterPoll(CallbackInfo ci) {
        if (keyDownBuffer == null)
            return;

        int keys = Math.min(InputKeyboard.KEY_COUNT, keyDownBuffer.capacity());
        for (int key = 0; key < keys; key++) {
            byte real = keyDownBuffer.get(key);
            int previousReal = InputKeyboard.realStates.getAndSet(key, real);

            int override = InputKeyboard.overrideStates.get(key);
            if (override != InputKeyboard.STATE_PASSTHROUGH) {
                // Keep a native edge from leaking through an active override.
                if (previousReal != real && override != real)
                    InputKeyboard.pendingEvents.add(new InputKeyboard.Event(key, (byte) override));
                keyDownBuffer.put(key, (byte) override);
            }
        }

        InputKeyboard.realSpace = InputKeyboard.real(Keyboard.KEY_SPACE);
        InputKeyboard.pollSerial++;
    }

    @Inject(method = "next", at = @At("HEAD"), remap = false)
    private static void onNext(CallbackInfoReturnable<Boolean> cir) {
        InputKeyboard.Event event = InputKeyboard.pendingEvents.peek();
        if (event == null || readBuffer == null)
            return;

        final int EVENT_SIZE = 18;
        if (readBuffer.capacity() - readBuffer.limit() < EVENT_SIZE)
            return;

        InputKeyboard.pendingEvents.poll();

        int oldPosition = readBuffer.position();
        readBuffer.position(readBuffer.limit());
        readBuffer.limit(readBuffer.capacity());

        readBuffer.putInt(event.key);
        readBuffer.put(event.state);
        readBuffer.putInt(Keyboard.CHAR_NONE);
        readBuffer.putLong(System.nanoTime());
        readBuffer.put((byte) 0); // not a repeat event

        readBuffer.limit(readBuffer.position());
        readBuffer.position(oldPosition);
    }
}
