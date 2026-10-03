package com.example.addon.gui;

import com.example.addon.modules.SchematicBuild;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.settings.Setting;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * AutoBuild menu: tabs "Công trình / Vật liệu / Tùy chỉnh", VI/EN switch, progress, file picker and drag & drop.
 */
public class BuildMenuScreen extends WindowScreen {
    private final SchematicBuild m;
    private int tab = 0;
    private int tickCounter = 0;

    private WLabel layerLbl, layerBar, totalLbl, totalBar, statusLbl, fileLbl;

    public BuildMenuScreen(GuiTheme theme, SchematicBuild module) {
        super(theme, "Schematic · AutoBuild");
        this.m = module;
    }

    @Override
    public void initWidgets() {
        // language switch
        WHorizontalList lang = add(theme.horizontalList()).expandX().widget();
        lang.add(theme.label(m.t("Ngôn ngữ", "Language"))).expandX();
        WButton vi = lang.add(theme.button(m.vietnamese.get() ? "[VI]" : "VI")).widget();
        vi.action = () -> { m.setVietnamese(true); reload(); };
        WButton en = lang.add(theme.button(m.vietnamese.get() ? "EN" : "[EN]")).widget();
        en.action = () -> { m.setVietnamese(false); reload(); };

        // tabs
        String[] names = { m.t("Công trình", "Build"), m.t("Vật liệu", "Materials"), m.t("Tùy chỉnh", "Options") };
        WHorizontalList tabs = add(theme.horizontalList()).expandX().widget();
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            WButton b = tabs.add(theme.button((i == tab ? "▶ " : "") + names[i])).expandX().widget();
            b.action = () -> { tab = idx; reload(); };
        }
        add(theme.horizontalSeparator()).expandX();

        switch (tab) {
            case 0 -> buildTab();
            case 1 -> materialsTab();
            default -> optionsTab();
        }
    }

    // ------------------------------------------------------------------ tab 0: build

    private void buildTab() {
        add(theme.label(m.t("TIẾN ĐỘ CÔNG TRÌNH", "BUILD PROGRESS")));
        layerLbl = add(theme.label("")).widget();
        layerBar = add(theme.label("")).widget();
        totalLbl = add(theme.label("")).widget();
        totalBar = add(theme.label("")).widget();
        statusLbl = add(theme.label("")).widget();
        add(theme.horizontalSeparator()).expandX();

        add(theme.label(m.t("Schematic hiện tại", "Current schematic")));
        WHorizontalList f = add(theme.horizontalList()).expandX().widget();
        fileLbl = f.add(theme.label("")).expandX().widget();
        WButton load = f.add(theme.button(m.t("Nạp schematic", "Load schematic"))).widget();
        load.action = m::pickFile;
        add(theme.label(m.t("(hoặc kéo-thả file .litematic vào cửa sổ này)", "(or drag & drop a .litematic file onto this window)")));

        WHorizontalList y = add(theme.horizontalList()).expandX().widget();
        y.add(theme.label(m.t("Y bắt đầu", "Y start")));
        WTextBox ys = y.add(theme.textBox(m.yMinText())).minWidth(70).widget();
        ys.action = () -> m.setYMin(ys.get());
        y.add(theme.label(m.t("Y kết thúc", "Y end")));
        WTextBox ye = y.add(theme.textBox(m.yMaxText())).minWidth(70).widget();
        ye.action = () -> m.setYMax(ye.get());
        add(theme.label(m.t("Để trống = tất cả các tầng (Y là toạ độ thế giới).", "Leave empty = all layers (Y is world height).")));

        WHorizontalList run = add(theme.horizontalList()).expandX().widget();
        WButton start = run.add(theme.button("▶ " + m.t("Bắt đầu / Tiếp tục", "Start / Resume"))).expandX().widget();
        start.action = m::start;
        WButton pause = run.add(theme.button("⏸ " + m.t("Tạm dừng", "Pause"))).expandX().widget();
        pause.action = m::pause;

        WHorizontalList extra = add(theme.horizontalList()).expandX().widget();
        WButton restore = extra.add(theme.button(m.t("Khôi phục", "Restore"))).expandX().widget();
        restore.action = () -> { m.restore(); reload(); };
        WButton bad = extra.add(theme.button(m.t("Block lỗi", "Bad blocks") + " (" + m.unmatchedCount() + ")")).expandX().widget();
        bad.action = () -> { m.retryUnmatched(); reload(); };
        WButton clean = extra.add(theme.button(m.t("Dọn support", "Clean supports"))).expandX().widget();
        clean.action = m::cleanScaffolds;

        add(theme.label(m.vietnamese.get()
            ? "Xây hết X/Z trong một tầng Y trước khi lên tầng (tuỳ chọn \"strict-layers\")."
            : "Finishes every X/Z of a Y layer before moving up (option \"strict-layers\")."));
        refreshLabels();
    }

    private static String bar(double pct) {
        int total = 24;
        int filled = (int) Math.round(Math.max(0, Math.min(100, pct)) / 100.0 * total);
        return "█".repeat(filled) + "░".repeat(total - filled);
    }

    private void refreshLabels() {
        if (layerLbl == null) return;
        if (m.hasLayer()) {
            double p = SchematicBuild.pct(m.layerDone(), m.layerTotal());
            layerLbl.set(String.format(Locale.ROOT, "Layer Y = %d   %d / %d   %.1f%%", m.layerY(), m.layerDone(), m.layerTotal(), p));
            layerBar.set(bar(p));
        } else {
            layerLbl.set("Layer: -");
            layerBar.set(bar(0));
        }
        double tp = SchematicBuild.pct(m.doneCount(), m.totalCount());
        totalLbl.set(String.format(Locale.ROOT, "%s   %d / %d   %.1f%%", m.t("Toàn bộ schematic", "Whole schematic"), m.doneCount(), m.totalCount(), tp));
        totalBar.set(bar(tp));
        statusLbl.set("● " + m.statusText());
        fileLbl.set(m.loadedName().isEmpty() ? m.t("(chưa chọn)", "(none)") : m.loadedName());
    }

    // ------------------------------------------------------------------ tab 1: materials

    private void materialsTab() {
        if (!m.hasLayer()) {
            add(theme.label(m.t("Chưa có tầng nào đang xây. Hãy nạp schematic ở tab Công trình.", "No layer in progress. Load a schematic in the Build tab.")));
            return;
        }
        add(theme.label(String.format(Locale.ROOT, "Layer Y = %d · %s %d · Hotbar %d · %s %d",
            m.layerY(), m.t("Cần", "Required"), m.needSum(), m.haveSum(), m.t("Thiếu", "Missing"), m.needSum() - m.haveSum())));
        add(theme.label(m.t("Chỉ tính vật phẩm trong hotbar (module chỉ đặt block từ hotbar).", "Only the hotbar counts (the module only places from the hotbar).")));
        add(theme.horizontalSeparator()).expandX();

        WTable table = add(theme.table()).expandX().widget();
        List<SchematicBuild.Need> needs = m.needs();
        int shown = 0;
        for (SchematicBuild.Need n : needs) {
            if (shown++ >= 40) break;
            boolean ok = n.have() >= n.need();
            table.add(theme.label(n.name())).expandCellX();
            table.add(theme.label(n.have() + " / " + n.need() + (ok ? "  ✓" : "  ✗")));
            table.row();
        }
        if (needs.size() > 40) add(theme.label("... +" + (needs.size() - 40)));
    }

    // ------------------------------------------------------------------ tab 2: options

    private void optionsTab() {
        option(m.t("Dùng block scaffold tạm", "Use temporary scaffold"), m.useScaffold);
        option(m.t("Chỉnh trạng thái (repeater, cửa, cần gạt...)", "Fix states (repeaters, doors, levers...)"), m.fixStates);
        option(m.t("Xây hết tầng rồi mới lên tầng", "Finish a layer before the next"), m.strictLayers);
        option(m.t("Tự bắt đầu sau khi nạp file", "Auto-start after loading"), m.autoStart);
        option(m.t("Hiện HUD tiến độ", "Show progress HUD"), m.hud);
        add(theme.horizontalSeparator()).expandX();
        add(theme.label(m.t("Các thiết lập khác (range, delay, forward-offset, phím tắt, scaffold-block...) nằm trong cài đặt của module.",
            "Other settings (range, delay, forward-offset, keybinds, scaffold-block...) are in the module's settings.")));
    }

    private void option(String label, Setting<Boolean> setting) {
        WHorizontalList row = add(theme.horizontalList()).expandX().widget();
        row.add(theme.label(label)).expandX();
        WCheckbox cb = row.add(theme.checkbox(setting.get())).widget();
        cb.action = () -> setting.set(cb.checked);
    }

    // ------------------------------------------------------------------ live refresh + drag & drop

    @Override
    public void tick() {
        super.tick();
        if (tab == 0) refreshLabels();
        else if (tab == 1 && ++tickCounter % 20 == 0) reload();
    }

    @Override
    public void onFilesDropped(List<Path> paths) {
        if (paths == null || paths.isEmpty()) return;
        m.selectFile(paths.get(0));
        tab = 0;
        reload();
    }
}
