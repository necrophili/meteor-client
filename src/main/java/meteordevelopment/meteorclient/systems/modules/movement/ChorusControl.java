/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client/).
 * Copyright (c) 2021 Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import meteordevelopment.meteorclient.events.entity.player.FinishUsingItemEvent;
import meteordevelopment.meteorclient.events.entity.player.TeleportParticleEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerEntity;
import meteordevelopment.meteorclient.utils.entity.fakeplayer.FakePlayerManager;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.TeleportConfirmC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class ChorusControl extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<PositionMode> positionMode = sgGeneral.add(new EnumSetting.Builder<PositionMode>()
        .name("position-mode")
        .description("How your teleport position is calculated.")
        .defaultValue(PositionMode.Particle)
        .build()
    );

    private final Setting<Boolean> onItemSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("tp-on-switch")
        .description("Teleports you when you switch items.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onDeactivate = sgGeneral.add(new BoolSetting.Builder()
        .name("tp-on-deactivate")
        .description("Teleports you when the module is deactivated.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Keybind> onKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("on-key")
        .description("Teleports when a key is pressed.")
        .defaultValue(Keybind.none())
        .action(this::sendPackets)
        .build()
    );

    private final Setting<Boolean> autoTeleport = sgGeneral.add(new BoolSetting.Builder()
        .name("automatically-teleport")
        .description("Automatically teleports you after a fixed number of ticks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> ticksToTeleport = sgGeneral.add(new IntSetting.Builder()
        .name("ticks-to-teleport")
        .description("The amount of ticks to wait before automatically teleporting.")
        .defaultValue(40)
        .min(0)
        .sliderMax(100)
        .visible(autoTeleport::get)
        .build()
    );

    //render
    private final Setting<Boolean> renderActual = sgRender.add(new BoolSetting.Builder()
        .name("set-position")
        .description("Sets you clientside to your actual position.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fakeplayerOnDestination = sgRender.add(new BoolSetting.Builder()
        .name("fakeplayer-on-destination")
        .description("Creates a fakeplayer at the destination.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> drawLine = sgRender.add(new BoolSetting.Builder()
        .name("draw-line")
        .description("Draws a line to where you are going to be.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> lineColour = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The lines color.")
        .defaultValue(new SettingColor(205, 205, 205, 127))
        .visible(drawLine::get)
        .build()
    );

    private int slot;
    private int delay = 0;
    private boolean ateChorus, sending, fakePlayerSpawned, gotPosition = false;
    private double posX, posY, posZ, cposX, cposY, cposZ;
    private FakePlayerEntity fakePlayer = null;
    private final Queue<TeleportConfirmC2SPacket> telePackets = new LinkedList<>();


    public ChorusControl() {
        super(Categories.Player, "chorus-control", "Delays teleporting with a chorus fruit.");
    }

    @Override
    public void onActivate() {
        ateChorus = false;
        delay = 0;
        telePackets.clear();
        fakePlayerSpawned = false;
        gotPosition = false;
    }

    @Override
    public void onDeactivate() {
        if (Utils.canUpdate() && ateChorus && onDeactivate.get()) {
            sendPackets();
        }
        telePackets.clear();
        fakePlayerSpawned = false;
        gotPosition = false;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (event.packet instanceof TeleportConfirmC2SPacket telepacket && ateChorus && !sending) {
            telePackets.add(telepacket);
            event.cancel();
        }
    }

    @EventHandler
    private void onPacketRecieve(PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket posPacket && ateChorus) {
            event.setCancelled(true);
            if (positionMode.get() == PositionMode.PosLook) {
                cposX = posPacket.getX();
                cposY = posPacket.getY();
                cposZ = posPacket.getZ();
                gotPosition = true;
                if (fakeplayerOnDestination.get() && renderActual.get() && !fakePlayerSpawned) spawnFakeplayer(cposX, cposY, cposZ);
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (ateChorus) {
            delay++;
            if (!mc.player.getPos().equals(new BlockPos(posX, posY, posZ)) && renderActual.get()) {
                mc.player.setPos(posX, posY, posZ);
            }

            if (autoTeleport.get() && delay >= ticksToTeleport.get()) {
                sendPackets();
            }

            if (onItemSwitch.get() && slot != mc.player.getInventory().selectedSlot) {
                sendPackets();
            }
        }
    }

    @EventHandler
    private void onEat(FinishUsingItemEvent event) {
        if (event.itemStack.getItem().equals(Items.CHORUS_FRUIT)) {
            posX = mc.player.getX();
            posY = mc.player.getY();
            posZ = mc.player.getZ();
            ateChorus = true;
            slot = mc.player.getInventory().selectedSlot;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (drawLine.get() && ateChorus && gotPosition) {
            event.renderer.line(RenderUtils.center.x, RenderUtils.center.y, RenderUtils.center.z, cposX, cposY, cposZ, lineColour.get());
        }
    }

    @EventHandler
    private void onTeleportParticle(TeleportParticleEvent event) {
        if (ateChorus && positionMode.get() == PositionMode.Particle) {
            cposX = event.x;
            cposY = event.y;
            cposZ = event.z;
            gotPosition = true;
            if (fakeplayerOnDestination.get() && renderActual.get() && !fakePlayerSpawned) spawnFakeplayer(cposX, cposY, cposZ);
        }
    }

    private void sendPackets() {
        sending = true;

        while (!telePackets.isEmpty()) {
            mc.getNetworkHandler().sendPacket(telePackets.poll());
        }

        delay = 0;
        ateChorus = false;
        sending = false;
        gotPosition = false;

        if (fakePlayer != null) {
            FakePlayerManager.fakePlayers.remove(fakePlayer);
            fakePlayer.despawn();
            fakePlayer = null;
            fakePlayerSpawned = false;
        }
    }

    private void spawnFakeplayer(double x, double y, double z) {
        fakePlayer = new FakePlayerEntity(mc.player, mc.player.getEntityName(), mc.player.getHealth(), false);
        fakePlayer.spawn();
        fakePlayer.setPos(x, y ,z);
        FakePlayerManager.fakePlayers.add(fakePlayer);
        fakePlayerSpawned = true;
    }

    @Override
    public String getInfoString() {
        if (autoTeleport.get() && ateChorus) return String.valueOf(ticksToTeleport.get() - delay);
        return null;
    }

    public enum PositionMode {
        Particle,
        PosLook,
        None
    }
}
