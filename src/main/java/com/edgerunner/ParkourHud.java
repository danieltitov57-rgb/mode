package com.edgerunner;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.Style;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Hides the vanilla HUD in parkour mode and draws a compact controls helper + status strip. */
@Mod.EventBusSubscriber(modid = EdgeRunner.MODID, value = Dist.CLIENT)
public class ParkourHud {

    private static final Style FONT = Style.EMPTY.withFont(new ResourceLocation(EdgeRunner.MODID, "ui"));

    private static final int BG = 0x9E0B0D12;
    private static final int ACCENT = 0xFFE8392E;
    private static final int TEXT = 0xFFEDEDED;
    private static final int DIM = 0xFF8E949C;
    private static final int KEY_LINE = 0xFF5A616B;

    private static ITextComponent t(String s) {
        return new StringTextComponent(s).withStyle(FONT);
    }

    // -------------------------------------------------------- hide vanilla HUD

    @SubscribeEvent
    public static void onOverlayPre(RenderGameOverlayEvent.Pre e) {
        if (!ParkourClient.isEnabled()) return;
        switch (e.getType()) {
            case ARMOR:
            case HEALTH:
            case FOOD:
            case AIR:
            case HOTBAR:
            case EXPERIENCE:
            case HEALTHMOUNT:
            case JUMPBAR:
                e.setCanceled(true);
                break;
            default:
                break;
        }
    }

    // -------------------------------------------------------------- draw HUD

    @SubscribeEvent
    public static void onOverlayPost(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        if (!ParkourClient.isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null) return;

        MatrixStack ms = e.getMatrixStack();
        FontRenderer font = mc.font;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();

        drawStatus(ms, font, sw, sh);
        if (ParkourClient.isHelpVisible()) {
            drawHelp(ms, font, mc);
        }
    }

    private static void drawStatus(MatrixStack ms, FontRenderer font, int sw, int sh) {
        ITextComponent c = t(ParkourClient.getStateLabel());
        int w = font.width(c);
        int barW = 90;
        int x = (sw - barW) / 2;
        int y = sh - 18;

        font.drawShadow(ms, c, (sw - w) / 2.0F, y - 11, TEXT);

        AbstractGui.fill(ms, x, y, x + barW, y + 2, 0x80000000);
        int filled = (int) Math.round(barW * ParkourClient.getMomentum());
        if (filled > 0) {
            AbstractGui.fill(ms, x, y, x + filled, y + 2, ACCENT);
        }
    }

    private static void drawHelp(MatrixStack ms, FontRenderer font, Minecraft mc) {
        String sprint = key(mc.options.keySprint.getTranslatedKeyMessage().getString());
        String shift = key(mc.options.keyShift.getTranslatedKeyMessage().getString());
        String jump = key(mc.options.keyJump.getTranslatedKeyMessage().getString());
        String fwd = key(mc.options.keyUp.getTranslatedKeyMessage().getString());
        String back = key(mc.options.keyDown.getTranslatedKeyMessage().getString());
        String left = key(mc.options.keyLeft.getTranslatedKeyMessage().getString());
        String right = key(mc.options.keyRight.getTranslatedKeyMessage().getString());
        String toggle = key(ParkourClient.TOGGLE_KEY.getTranslatedKeyMessage().getString());
        String help = key(ParkourClient.HELP_KEY.getTranslatedKeyMessage().getString());

        boolean katana = mc.player.getMainHandItem().getItem() instanceof KatanaItem;

        java.util.List<String[]> keys = new java.util.ArrayList<>();
        java.util.List<String> desc = new java.util.ArrayList<>();
        add(keys, desc, new String[] {sprint}, "Разгон");
        add(keys, desc, new String[] {shift}, "Подкат");
        add(keys, desc, new String[] {jump}, "У стены: бег / прыжок");
        add(keys, desc, new String[] {fwd, jump}, "Вскок · лазание");
        add(keys, desc, new String[] {left, right}, "По стене вбок");
        add(keys, desc, new String[] {back, jump}, "Отскок от стены");
        if (katana) {
            add(keys, desc, new String[] {"ЛКМ"}, "Удар");
            add(keys, desc, new String[] {"ПКМ"}, "Рывок");
        }

        float scale = 0.72F;
        int rowH = 11;
        int keyGap = 2;
        int descGap = 5;

        int maxKeys = 0;
        for (String[] ks : keys) {
            int kw = 0;
            for (String k : ks) kw += keyWidth(font, k) + keyGap;
            maxKeys = Math.max(maxKeys, kw);
        }
        int maxDesc = 0;
        for (String d : desc) maxDesc = Math.max(maxDesc, font.width(t(d)));

        ITextComponent title = t("EDGE RUNNER");
        ITextComponent hint = t(toggle + " · " + help);
        int innerW = Math.max(maxKeys + descGap + maxDesc, font.width(title) + 10 + font.width(hint));
        int panelW = innerW + 12;
        int panelH = 18 + keys.size() * rowH + 3;

        ms.pushPose();
        ms.translate(6.0D, 6.0D, 0.0D);
        ms.scale(scale, scale, 1.0F);

        AbstractGui.fill(ms, 0, 0, panelW, panelH, BG);
        AbstractGui.fill(ms, 0, 0, panelW, 1, ACCENT);

        font.drawShadow(ms, title, 6, 5, TEXT);
        font.drawShadow(ms, hint, panelW - 6 - font.width(hint), 5, DIM);

        int y = 17;
        for (int i = 0; i < keys.size(); i++) {
            int kx = 6;
            for (String k : keys.get(i)) {
                kx += drawKey(ms, font, kx, y, k) + keyGap;
            }
            font.drawShadow(ms, t(desc.get(i)), 6 + maxKeys + descGap, y + 2, TEXT);
            y += rowH;
        }
        ms.popPose();
    }

    private static void add(java.util.List<String[]> keys, java.util.List<String> desc, String[] k, String d) {
        keys.add(k);
        desc.add(d);
    }

    private static String key(String s) {
        return s == null ? "?" : s.toUpperCase();
    }

    private static int keyWidth(FontRenderer font, String label) {
        return Math.max(11, font.width(t(label)) + 6);
    }

    private static int drawKey(MatrixStack ms, FontRenderer font, int x, int y, String label) {
        ITextComponent c = t(label);
        int w = keyWidth(font, label);
        // outlined keycap
        AbstractGui.fill(ms, x, y, x + w, y + 10, KEY_LINE);
        AbstractGui.fill(ms, x + 1, y + 1, x + w - 1, y + 9, 0xFF14171C);
        font.drawShadow(ms, c, x + (w - font.width(c)) / 2.0F, y + 1, TEXT);
        return w;
    }
}
