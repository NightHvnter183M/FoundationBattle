package main;

import arc.struct.ObjectMap;
import arc.struct.Seq;
import mindustry.gen.Call;
import mindustry.gen.Player;
import mindustry.ui.Menus;

public final class SnapshotMenu {
    private static final Seq<SnapshotMenu> all = new Seq<>();

    private final int id;
    private final ObjectMap<String, Seq<Runnable>> pending = new ObjectMap<>();

    public SnapshotMenu() {
        id = Menus.registerMenu((player, option) -> {
            if (player == null) return;
            Seq<Runnable> actions = pending.remove(player.uuid());
            if (actions != null && option >= 0 && option < actions.size) actions.get(option).run();
        });
        all.add(this);
    }

    public void show(Player p, String title, String message, Options options) {
        pending.put(p.uuid(), options.actions);
        Call.menu(p.con, id, title, message, options.rows.toArray(String[].class));
    }

    public static void forget(String uuid) {
        for (int i = 0; i < all.size; i++) all.get(i).pending.remove(uuid);
    }
    public static void forgetAll() {
        for (int i = 0; i < all.size; i++) all.get(i).pending.clear();
    }
    public static class Options {
        private final Seq<String[]> rows = new Seq<>();
        private final Seq<Runnable> actions = new Seq<>();
        public Options add(String label, Runnable action) {
            rows.add(new String[]{label});
            actions.add(action);
            return this;
        }
        public Options row(String left, Runnable leftAction, String right, Runnable rightAction) {
            rows.add(new String[]{left, right});
            actions.add(leftAction);
            actions.add(rightAction);
            return this;
        }

        public boolean isEmpty() {
            return actions.isEmpty();
        }
    }
}
