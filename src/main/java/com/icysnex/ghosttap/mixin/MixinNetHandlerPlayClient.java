package com.icysnex.ghosttap.mixin;

import com.icysnex.ghosttap.core.jump.AutoJumpReset;
import com.icysnex.ghosttap.core.analytics.Tracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Observes vanilla's local hurt + velocity sequence without changing either
// packet. TAIL only runs after PacketThreadUtil has moved handling to the client
// thread and after Minecraft has applied the packet.
@Mixin(NetHandlerPlayClient.class)
public abstract class MixinNetHandlerPlayClient {

    @Inject(method = "handleEntityStatus(Lnet/minecraft/network/play/server/S19PacketEntityStatus;)V", at = @At("TAIL"))
    private void ghostTap$onEntityStatus(S19PacketEntityStatus packet, CallbackInfo ci) {
        if (packet.getOpCode() != 2)
            return;

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && mc.theWorld != null && packet.getEntity(mc.theWorld) == player)
            AutoJumpReset.INSTANCE.onLocalHurtStatus();
    }

    @Inject(method = "handleEntityVelocity(Lnet/minecraft/network/play/server/S12PacketEntityVelocity;)V", at = @At("TAIL"))
    private void ghostTap$onEntityVelocity(S12PacketEntityVelocity packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        if (player != null && packet.getEntityID() == player.getEntityId()) {
            long velocityNanos = Tracker.enabled ? System.nanoTime() : -1;
            AutoJumpReset.INSTANCE.onLocalVelocity(player, mc.currentScreen != null,
                    packet.getMotionX(), packet.getMotionY(), packet.getMotionZ(), velocityNanos);
        }
    }
}
