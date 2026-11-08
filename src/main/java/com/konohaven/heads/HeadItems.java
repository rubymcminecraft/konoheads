package com.konohaven.heads;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public final class HeadItems {
    private HeadItems() {
    }

    /** Base64 "textures" property value pointing at textures.minecraft.net/texture/<hash>. */
    public static String textureValue(String hash) {
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/" + hash + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Stable UUID per texture, so identical heads stack. */
    private static UUID uuidFor(String hash) {
        return UUID.nameUUIDFromBytes(("konoheads:" + hash).getBytes(StandardCharsets.UTF_8));
    }

    public static ItemStack stack(HeadDatabase.Head head) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", textureValue(head.hash()))));
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(uuidFor(head.hash()), "", props)));
        stack.set(DataComponents.ITEM_NAME, Component.literal(head.name()));
        return stack;
    }

    /** A vanilla /give command (without the leading slash) that produces the same head. */
    public static String giveCommand(HeadDatabase.Head head) {
        String name = head.name().replace("\\", "\\\\").replace("'", "\\'");
        return "give @s player_head[item_name='" + name + "',profile={properties:[{name:\"textures\",value:\""
                + textureValue(head.hash()) + "\"}]}]";
    }

    /**
     * Creative: put the head straight into the inventory (first free slot, else the selected hotbar slot).
     * Otherwise: run /give (works if you have permission). The command is also copied to the clipboard.
     */
    public static Component give(HeadDatabase.Head head) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return Component.literal("Not in a world");

        String cmd = giveCommand(head);
        mc.keyboardHandler.setClipboard("/" + cmd);

        if (mc.gameMode.getPlayerMode().isCreative()) {
            Inventory inv = mc.player.getInventory();
            int slot = inv.getFreeSlot();
            if (slot < 0 || slot >= 36) slot = inv.getSelectedSlot();
            ItemStack stack = stack(head);
            inv.setItem(slot, stack);
            // container slot ids: hotbar 0-8 -> 36-44, main inventory 9-35 -> 9-35
            int menuSlot = Inventory.isHotbarSlot(slot) ? 36 + slot : slot;
            mc.gameMode.handleCreativeModeItemAdd(stack, menuSlot);
            return Component.literal("Got " + head.name());
        }

        mc.player.connection.sendCommand(cmd);
        return Component.literal("Sent /give for " + head.name() + " (also copied to clipboard)");
    }
}
