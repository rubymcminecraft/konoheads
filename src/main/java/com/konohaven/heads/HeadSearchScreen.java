package com.konohaven.heads;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HeadSearchScreen extends Screen {
    private static final int CELL = 20;
    private static final int TOP = 46;

    private static String lastQuery = "";
    private static int lastCategory = -1;

    private final HeadDatabase db = KonoHeads.DB;
    private final Map<String, ItemStack> stackCache = new HashMap<>();
    private final String initialQuery;

    private EditBox search;
    private Button categoryButton;
    private List<HeadDatabase.Head> results = List.of();
    private List<HeadDatabase.Head> source = null; // the db list the results were computed from
    private int scrollRow = 0;
    private int categoryId;
    private Component message = null;
    private long messageUntil = 0;

    public HeadSearchScreen(String query) {
        super(Component.literal("Head Search"));
        this.initialQuery = query != null ? query : lastQuery;
        this.categoryId = lastCategory;
    }

    @Override
    protected void init() {
        db.ensureLoaded();

        int buttonsW = 100 + 4 + 60;
        search = new EditBox(font, 10, 10, Math.max(80, width - 20 - buttonsW - 4), 20, Component.literal("Search"));
        search.setHint(Component.literal("Search heads..."));
        search.setMaxLength(100);
        search.setValue(initialQuery);
        search.setResponder(s -> updateResults());
        addRenderableWidget(search);

        categoryButton = Button.builder(categoryLabel(), b -> cycleCategory())
                .bounds(width - 10 - buttonsW, 10, 100, 20).build();
        addRenderableWidget(categoryButton);

        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> {
            stackCache.clear();
            db.refresh();
            source = null;
        }).bounds(width - 10 - 60, 10, 60, 20).build());

        setInitialFocus(search);
        updateResults();
    }

    private Component categoryLabel() {
        String name = categoryId == -1 ? "All" : db.categories().getOrDefault(categoryId, "?");
        return Component.literal(name);
    }

    private void cycleCategory() {
        List<Integer> ids = new ArrayList<>(db.categories().keySet());
        if (ids.isEmpty()) {
            categoryId = -1;
        } else {
            int i = ids.indexOf(categoryId);
            categoryId = (i + 1 >= ids.size()) ? -1 : ids.get(i + 1);
        }
        categoryButton.setMessage(categoryLabel());
        updateResults();
    }

    private void updateResults() {
        source = db.heads();
        results = db.search(search.getValue(), categoryId);
        scrollRow = 0;
        lastQuery = search.getValue();
        lastCategory = categoryId;
    }

    private int cols() { return Math.max(1, (width - 20) / CELL); }
    private int rows() { return Math.max(1, (height - TOP - 8) / CELL); }
    private int maxScroll() { return Math.max(0, (results.size() + cols() - 1) / cols() - rows()); }

    private int gridLeft() { return (width - cols() * CELL) / 2; }

    /** Index into results under the mouse, or -1. */
    private int indexAt(double mx, double my) {
        int left = gridLeft();
        if (mx < left || my < TOP) return -1;
        int c = (int) ((mx - left) / CELL);
        int r = (int) ((my - TOP) / CELL);
        if (c >= cols() || r >= rows()) return -1;
        int i = (scrollRow + r) * cols() + c;
        return i < results.size() ? i : -1;
    }

    private ItemStack stackFor(HeadDatabase.Head h) {
        return stackCache.computeIfAbsent(h.hash(), k -> HeadItems.stack(h));
    }

    @Override
    public void tick() {
        // the db finished (re)loading in the background: re-run the search
        if (source != db.heads()) {
            categoryButton.setMessage(categoryLabel());
            updateResults();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);

        String status = db.status();
        if (!db.loading() && !db.heads().isEmpty()) {
            status = results.size() + " / " + db.heads().size() + " heads  |  license: " + db.license();
        }
        Component line;
        if (message != null && System.currentTimeMillis() < messageUntil) line = message;
        else if (!db.warnings().isEmpty() && !db.loading()) line = Component.literal("API: " + db.warnings()).withColor(0xFFFFC107);
        else line = Component.literal(status);
        g.text(font, line, 10, 34, 0xFFA0A0A0);

        // credit required by the minecraft-heads.com terms of use
        String credit = "Heads from Minecraft-Heads.com";
        g.text(font, credit, width - 10 - font.width(credit), 34, 0xFF6FA8DC);

        int cols = cols();
        int rows = rows();
        int left = gridLeft();
        int hovered = indexAt(mouseX, mouseY);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int i = (scrollRow + r) * cols + c;
                if (i >= results.size()) break;
                int x = left + c * CELL;
                int y = TOP + r * CELL;
                g.fill(x, y, x + CELL - 2, y + CELL - 2, i == hovered ? 0x80FFFFFF : 0x40000000);
                g.item(stackFor(results.get(i)), x + 1, y + 1);
            }
        }

        if (maxScroll() > 0) {
            int trackH = rows * CELL;
            int barH = Math.max(10, trackH * rows / (rows + maxScroll()));
            int barY = TOP + (trackH - barH) * scrollRow / maxScroll();
            int x = width - 6;
            g.fill(x, TOP, x + 3, TOP + trackH, 0x40000000);
            g.fill(x, barY, x + 3, barY + barH, 0xC0FFFFFF);
        }

        if (hovered >= 0) {
            HeadDatabase.Head h = results.get(hovered);
            List<Component> tip = new ArrayList<>();
            tip.add(Component.literal(h.name()));
            String cat = db.categories().get(h.category());
            if (cat != null) tip.add(Component.literal(cat).withColor(0xFF808080));
            tip.add(Component.literal("Left-click: get  ·  Right-click: copy /give").withColor(0xFF606060));
            g.setComponentTooltipForNextFrame(font, tip, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        int i = indexAt(event.x(), event.y());
        if (i < 0) return false;
        HeadDatabase.Head h = results.get(i);
        if (event.button() == 1) {
            minecraft.keyboardHandler.setClipboard("/" + HeadItems.giveCommand(h));
            flash(Component.literal("Copied /give for " + h.name()));
        } else {
            flash(HeadItems.give(h));
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        scrollRow = Mth.clamp(scrollRow - (int) Math.signum(sy) * 3, 0, maxScroll());
        return true;
    }

    private void flash(Component msg) {
        message = msg;
        messageUntil = System.currentTimeMillis() + 3000;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
