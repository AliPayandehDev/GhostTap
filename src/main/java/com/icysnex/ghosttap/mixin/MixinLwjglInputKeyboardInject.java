package com.icysnex.ghosttap.mixin;

import com.icysnex.ghosttap.core.input.InputKeyboard;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    @Unique
    private static byte ghostTap$previousRealSpace = InputKeyboard.STATE_UP;

    @Inject(method = "poll", at = @At("HEAD"), remap = false)
    private static void beforePoll(CallbackInfo ci) {
        if (keyDownBuffer != null)
            keyDownBuffer.put(Keyboard.KEY_SPACE, InputKeyboard.realSpace);
    }

    @Inject(method = "poll", at = @At("RETURN"), remap = false)
    private static void afterPoll(CallbackInfo ci) {
        if (keyDownBuffer == null)
            return;

        byte realSpace = keyDownBuffer.get(Keyboard.KEY_SPACE);
        InputKeyboard.realSpace = realSpace;

        byte spoofedSpace = InputKeyboard.spoofedSpace;
        if (InputKeyboard.pollSpaceLatch.getAndSet(false))
            spoofedSpace = InputKeyboard.STATE_DOWN;

        // A physical release event must not release a still-synthetic hold.
        // Queue a corrective down edge behind the native up edge.
        if (ghostTap$previousRealSpace == InputKeyboard.STATE_DOWN
                && realSpace == InputKeyboard.STATE_UP
                && spoofedSpace == InputKeyboard.STATE_DOWN) {
            InputKeyboard.pendingEvents.add(new InputKeyboard.Event(Keyboard.KEY_SPACE, InputKeyboard.STATE_DOWN));
        }

        keyDownBuffer.put(Keyboard.KEY_SPACE, (byte) (realSpace | spoofedSpace));
        ghostTap$previousRealSpace = realSpace;
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
