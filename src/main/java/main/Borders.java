package main;

import arc.Core;
import arc.Events;
import arc.graphics.Color;
import arc.math.Angles;
import arc.math.Mathf;
import arc.math.geom.Point2;
import arc.struct.FloatSeq;
import arc.struct.IntIntMap;
import arc.struct.IntSet;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.Seq;
import arc.util.Log;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.game.Team;
import mindustry.game.Teams.TeamData;
import mindustry.gen.Call;
import mindustry.world.Tile;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class Borders {
    private static final MarkerApi markers = MarkerApi.find();

    private static IntIntMap markerTeams = new IntIntMap();
    private static IntIntMap removals = new IntIntMap();
    private static final ObjectMap<Team, IntSet> lamps = new ObjectMap<>();
    private static final ObjectSet<Team> dirty = new ObjectSet<>();
    private static boolean queued;

    public static void init() {
        Log.info("Foundation PvP: territory borders are drawn with @.",
            markers != null ? "world markers" : "illuminators (this server version has no world markers)");
        Events.on(EventType.CoreChangeEvent.class, e -> queue(e.core.team));
        Events.on(EventType.WorldLoadEvent.class, e -> {
            markerTeams = new IntIntMap();
            removals = new IntIntMap();
            lamps.clear();
        });
        if (markers != null) Timer.schedule(Borders::resendMarkers, 30f, 30f);
    }

    public static void queue(Team team) {
        dirty.add(team);
        if (queued) return;
        queued = true;
        Core.app.post(Borders::flush);
    }

    public static void clear(Team team) {
        if (markers != null) {
            queue(team);
            return;
        }
        IntSet placed = lamps.remove(team);
        if (placed != null) placed.each(pos -> removeLamp(pos, team));
    }

    private static void flush() {
        queued = false;
        Seq<Team> teams = dirty.toSeq();
        dirty.clear();
        if (markers != null) updateMarkers();
        else updateLamps(teams);
    }

    private static void updateMarkers() {
        IntIntMap now = new IntIntMap();
        float radius = Resources.BUILD_RADIUS * Vars.tilesize;
        FloatSeq arcs = new FloatSeq();
        for (TeamData data : Vars.state.teams.getActive()) {
            if (data.team == Team.derelict) continue;
            Seq<CoreBuild> cores = data.cores;
            for (int i = 0; i < cores.size; i++) {
                Tile center = cores.get(i).tile;
                visibleArcs(center, cores, i, radius, arcs);
                for (int a = 0; a < arcs.size; a += 2) {
                    int id = center.pos() * 8 + a / 2; // stable per core and arc
                    float start = arcs.get(a), end = arcs.get(a + 1);
                    int signature = 31 * (31 * data.team.id + Math.round(start)) + Math.round(end);
                    now.put(id, signature);
                    if (markerTeams.get(id, Integer.MIN_VALUE) != signature) {
                        markers.create(id, center, data.team.color, start, end);
                        removals.remove(id, 0);
                    }
                }
            }
        }
        for (IntIntMap.Entry e : markerTeams) {
            if (!now.containsKey(e.key)) {
                markers.remove(e.key);
                removals.put(e.key, 3);
            }
        }
        markerTeams = now;
    }

    private static void resendMarkers() {
        markerTeams = new IntIntMap(); // forces every current core to be sent again
        updateMarkers();
        IntIntMap left = new IntIntMap();
        for (IntIntMap.Entry e : removals) {
            markers.remove(e.key);
            if (e.value > 1) left.put(e.key, e.value - 1);
        }
        removals = left;
    }

    private static void visibleArcs(Tile center, Seq<CoreBuild> cores, int self, float radius, FloatSeq out) {
        out.clear();
        FloatSeq covered = new FloatSeq();
        for (int j = 0; j < cores.size; j++) {
            if (j == self) continue;
            Tile other = cores.get(j).tile;
            float dist = center.dst(other);
            if (dist >= radius * 2f) continue;
            if (dist < 0.01f) {
                if (j < self) return; // same spot: the earlier core draws the circle
                continue;
            }
            float mid = Angles.angle(center.worldx(), center.worldy(), other.worldx(), other.worldy());
            float half = (float) Math.acos(dist / (2f * radius)) * Mathf.radDeg;
            float from = mid - half, to = mid + half;
            if (from < 0f) { // split intervals that wrap around 0
                covered.add(from + 360f, 360f);
                covered.add(0f, to);
            } else if (to > 360f) {
                covered.add(from, 360f);
                covered.add(0f, to - 360f);
            } else {
                covered.add(from, to);
            }
        }

        int n = covered.size / 2;
        float[] starts = new float[n], ends = new float[n];
        Integer[] order = new Integer[n];
        for (int k = 0; k < n; k++) {
            starts[k] = covered.get(k * 2);
            ends[k] = covered.get(k * 2 + 1);
            order[k] = k;
        }
        java.util.Arrays.sort(order, (x, y) -> Float.compare(starts[x], starts[y]));

        float cursor = 0f;
        for (int k = 0; k < n; k++) {
            float s = starts[order[k]], e = ends[order[k]];
            if (s > cursor + 0.5f) out.add(cursor, s);
            cursor = Math.max(cursor, e);
        }
        if (cursor < 359.5f) out.add(cursor, 360f);

        if (out.size >= 4 && out.get(0) <= 0.01f && out.get(out.size - 1) >= 359.99f) {
            float start = out.get(out.size - 2) - 360f;
            out.set(0, start);
            out.size -= 2;
        }
        if (out.size > 16) out.size = 16;
    }

    private static void updateLamps(Seq<Team> teams) {
        ObjectMap<Team, IntSet> edges = new ObjectMap<>();
        for (Team team : teams) edges.put(team, edgeOf(team));

        for (Team team : teams) {
            IntSet old = lamps.get(team), edge = edges.get(team);
            if (old != null) old.each(pos -> {
                if (!edge.contains(pos)) removeLamp(pos, team);
            });
        }

        for (Team team : teams) {
            IntSet placed = new IntSet();
            edges.get(team).each(pos -> {
                Tile tile = Vars.world.tile(pos);
                if (tile == null) return;
                if (tile.block() == Blocks.illuminator && tile.team() == team) {
                    placed.add(pos);
                } else if (tile.block() == Blocks.air) {
                    tile.setNet(Blocks.illuminator, team, 0);
                    placed.add(pos);
                }
            });
            if (placed.isEmpty()) lamps.remove(team);
            else lamps.put(team, placed);
        }
    }

    private static void removeLamp(int pos, Team team) {
        Tile tile = Vars.world.tile(pos);
        if (tile != null && tile.block() == Blocks.illuminator && tile.team() == team) tile.removeNet();
    }

    private static IntSet edgeOf(Team team) {
        IntSet edge = new IntSet();
        Seq<CoreBuild> cores = team.cores();
        if (team == Team.derelict || cores.isEmpty()) return edge;

        int r = (int) Resources.BUILD_RADIUS, reach = 2 * r + 2;
        float r2 = Resources.BUILD_RADIUS * Resources.BUILD_RADIUS;
        int width = Vars.world.width(), height = Vars.world.height();
        Seq<Tile> near = new Seq<>();

        for (CoreBuild core : cores) {
            Tile c = core.tile;
            near.clear();
            for (CoreBuild other : cores) {
                if (Math.abs(other.tile.x - c.x) <= reach && Math.abs(other.tile.y - c.y) <= reach) near.add(other.tile);
            }
            int minX = Math.max(0, c.x - r - 1), maxX = Math.min(width - 1, c.x + r + 1);
            int minY = Math.max(0, c.y - r - 1), maxY = Math.min(height - 1, c.y + r + 1);
            for (int x = minX + (minX & 1); x <= maxX; x += 2) {
                for (int y = minY + (minY & 1); y <= maxY; y += 2) {
                    if (inside(x, y, near, r2) && (!inside(x + 1, y, near, r2) || !inside(x - 1, y, near, r2)
                        || !inside(x, y + 1, near, r2) || !inside(x, y - 1, near, r2))) {
                        edge.add(Point2.pack(x, y));
                    }
                }
            }
        }
        return edge;
    }

    private static boolean inside(int x, int y, Seq<Tile> cores, float r2) {
        for (int i = 0; i < cores.size; i++) {
            Tile c = cores.get(i);
            int dx = x - c.x, dy = y - c.y;
            if (dx * dx + dy * dy <= r2) return true;
        }
        return false;
    }

    private static final class MarkerApi {
        private final Constructor<?> shape;
        private final Field sides, stroke, color, startAngle, endAngle;
        private final Method create, remove;

        private MarkerApi(Constructor<?> shape, Field sides, Field stroke, Field color, Field startAngle, Field endAngle, Method create, Method remove) {
            this.shape = shape;
            this.sides = sides;
            this.stroke = stroke;
            this.color = color;
            this.startAngle = startAngle;
            this.endAngle = endAngle;
            this.create = create;
            this.remove = remove;
        }

        static MarkerApi find() {
            try {
                Class<?> base = Class.forName("mindustry.game.MapObjectives$ObjectiveMarker");
                Class<?> type = Class.forName("mindustry.game.MapObjectives$ShapeMarker");
                return new MarkerApi(
                    type.getConstructor(float.class, float.class, float.class, float.class),
                    type.getField("sides"), type.getField("stroke"), type.getField("color"),
                    type.getField("startAngle"), type.getField("endAngle"),
                    Call.class.getMethod("createMarker", int.class, base),
                    Call.class.getMethod("removeMarker", int.class));
            } catch (Throwable t) {
                return null;
            }
        }

        void create(int id, Tile center, Color teamColor, float start, float end) {
            try {
                Object marker = shape.newInstance(center.worldx(), center.worldy(), Resources.BUILD_RADIUS * Vars.tilesize, 0f);
                sides.setInt(marker, Math.max(3, Math.round((end - start) / 5f)));
                startAngle.setFloat(marker, start);
                endAngle.setFloat(marker, end);
                stroke.setFloat(marker, 2f);
                color.set(marker, teamColor.cpy());
                create.invoke(null, id, marker);
            } catch (Throwable t) {
                Log.err("Foundation PvP: can't create a border marker", t);
            }
        }

        void remove(int id) {
            try {
                remove.invoke(null, id);
            } catch (Throwable t) {
                Log.err("Foundation PvP: can't remove a border marker", t);
            }
        }
    }
}
