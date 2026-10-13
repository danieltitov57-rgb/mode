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

/** Hides the vanilla HUD in parkour mode and draws the controls helper + status strip. */
@Mod.EventBusSubscriber(modid = EdgeRunner.MODID, value = Dist.CLIENT)
public class ParkourHud {

    private static final Style FONT = Style.EMPTY.withFont(new ResourceLocation(EdgeRunner.MODID, "ui"));

    private static final int PANEL = 0xB00A0C10;
    private static final int ACCENT = 0xFFE8392E;
    private static final int TEXT = 0xFFF2F2F2;
    private static final int DIM = 0xFF9AA0A6;
    private static final int KEY_BG = 0xFF161A20;

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
        String label = ParkourClient.getStateLabel();
        ITextComponent c = t(label);
        int w = font.width(c);
        int barW = 140;
        int x = (sw - barW) / 2;
        int y = sh - 22;

        font.drawShadow(ms, c, (sw - w) / 2.0F, y - 13, TEXT);

        AbstractGui.fill(ms, x - 1, y - 1, x + barW + 1, y + 5, 0x99000000);
        AbstractGui.fill(ms, x, y, x + barW, y + 4, 0xFF1B1F26);
        int filled = (int) Math.round(barW * ParkourClient.getMomentum());
        if (filled > 0) {
            AbstractGui.fill(ms, x, y, x + filled, y + 4, ACCENT);
            AbstractGui.fill(ms, x, y, x + filled, y + 1, 0xFFFF8A80);
        }
    }

    private static void drawHelp(MatrixStack ms, FontRenderer font, Minecraft mc) {
        String sprint = key(mc.options.keySprint.getTranslatedKeyMessage().getString());
        String shift = key(mc.options.keyShift.getTranslatedKeyMessage().getString());
        String jump = key(mc.options.keyJump.getTranslatedKeyMessage().getString());
        String fwd = key(mc.options.keyUp.getTranslatedKeyMessage().getString());
        String toggle = key(ParkourClient.TOGGLE_KEY.getTranslatedKeyMessage().getString());
        String help = key(ParkourClient.HELP_KEY.getTranslatedKeyMessage().getString());

        String[][] keys = {
                {sprint},
                {shift},
                {jump},
                {jump},
                {fwd, jump},
                {}
        };
        String[] desc = {
                "Бег: копит разгон",
                "На бегу: подкат (хватит одного нажатия)",
                "В прыжке у стены: бег по стене",
                "На стене: прыжок от стены",
                "У уступа до 3 блоков: вскакивание",
                "Забор: баланс, медленный шаг"
        };

        int rowH = 15;
        int pad = 9;
        int keyGap = 3;
        int descGap = 7;

        // measure
        int maxKeys = 0;
        for (String[] ks : keys) {
            int kw = 0;
            for (String k : ks) kw += keyWidth(font, k) + keyGap;
            maxKeys = Math.max(maxKeys, kw);
        }
        int maxDesc = 0;
        for (String d : desc) maxDesc = Math.max(maxDesc, font.width(t(d)));

        ITextComponent title = t("EDGE RUNNER");
        ITextComponent footer = t(toggle + " режим   " + help + " подсказки");
        int contentW = Math.max(maxKeys + descGap + maxDesc, Math.max(font.width(title), font.width(footer)));
        int panelW = contentW + pad * 2 + 3;
        int panelH = 22 + desc.length * rowH + 20;

        int x0 = 8;
        int y0 = 8;

        AbstractGui.fill(ms, x0, y0, x0 + panelW, y0 + panelH, PANEL);
        AbstractGui.fill(ms, x0, y0, x0 + 2, y0 + panelH, ACCENT);

        int tx = x0 + pad + 2;
        font.drawShadow(ms, title, tx, y0 + 7, TEXT);
        AbstractGui.fill(ms, tx, y0 + 19, tx + 24, y0 + 20, ACCENT);

        int y = y0 + 26;
        for (int i = 0; i < desc.length; i++) {
            int kx = tx;
            for (String k : keys[i]) {
                kx += drawKey(ms, font, kx, y, k) + keyGap;
            }
            font.drawShadow(ms, t(desc[i]), tx + maxKeys + descGap, y + 2, i == 5 ? DIM : TEXT);
            y += rowH;
        }

        font.drawShadow(ms, footer, tx, y + 3, DIM);
    }

    private static String key(String s) {
        return s == null ? "?" : s.toUpperCase();
    }

    private static int keyWidth(FontRenderer font, String label) {
        return Math.max(14, font.width(t(label)) + 8);
    }

    private static int drawKey(MatrixStack ms, FontRenderer font, int x, int y, String label) {
        ITextComponent c = t(label);
        int w = keyWidth(font, label);
        AbstractGui.fill(ms, x, y, x + w, y + 13, ACCENT);
        AbstractGui.fill(ms, x + 1, y + 1, x + w - 1, y + 12, KEY_BG);
        font.drawShadow(ms, c, x + (w - font.width(c)) / 2.0F, y + 3, TEXT);
        return w;
    }
}
