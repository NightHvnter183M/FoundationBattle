package main;

import arc.Core;
import arc.files.Fi;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.OrderedMap;
import arc.struct.Seq;
import arc.util.Log;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class Bundle {
    public static final String BASE = "en";
    private static final Pattern FILE = Pattern.compile("bundle(?:_([A-Za-z]{2,3}(?:_[A-Za-z0-9]{2,4})?))?\\.properties");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{(\\d+)}");
    private static final String[] CATEGORIES = {"zero", "one", "two", "few", "many", "other"};
    public interface Text {
        String in(String lang);
    }

    private static String pluginId = "plugin";
    private static Fi dir;
    private static boolean loaded;
    private static final OrderedMap<String, ObjectMap<String, String>> langs = new OrderedMap<>();
    private static final ObjectMap<String, String> sources = new ObjectMap<>();
    private static String baseText = "";
    private static final ObjectMap<String, String> chosen = new ObjectMap<>();
    private static final ObjectSet<String> warned = new ObjectSet<>();
    private static String serverDefault = BASE;

    public static void init(String id) {
        if (loaded) return;
        loaded = true;
        pluginId = id;
        dir = Vars.dataDirectory.child(id);
        reload();
        loadChoices();
        String def = Core.settings.getString(id + "-default-language", BASE);
        serverDefault = langs.containsKey(def) ? def : BASE;
    }

    public static String lang(Player p) {
        if (p == null) return serverDefault;
        String picked = chosen.get(p.uuid());
        if (picked != null && langs.containsKey(picked)) return picked;
        String game = match(p.locale());
        return game != null ? game : serverDefault;
    }

    public static String get(Player p, String key, Object... args) {
        return get(lang(p), key, args);
    }

    public static String get(String lang, String key, Object... args) {
        String template = find(lang, key);
        return template == null ? missing(key) : format(lang, template, args);
    }

    public static String get(Player p, Text text) {
        return text.in(lang(p));
    }

    public static String find(String lang, String key) {
        for (String l = lang; l != null; l = parent(l)) {
            ObjectMap<String, String> texts = langs.get(l);
            String value = texts == null ? null : texts.get(key);
            if (value != null) return value;
        }
        return null;
    }

    public static String own(String lang, String key) {
        ObjectMap<String, String> texts = langs.get(lang);
        return texts == null ? null : texts.get(key);
    }

    public static Text text(String key, Object... args) {
        return lang -> get(lang, key, args);
    }

    public static Text count(String key, long n, Object... extra) {
        return lang -> {
            String template = null;
            for (String l = lang; l != null && template == null; l = parent(l)) {
                ObjectMap<String, String> texts = langs.get(l);
                if (texts == null) continue;
                template = texts.get(key + "." + category(l, n));
                if (template == null) template = texts.get(key + ".other");
                if (template == null) template = texts.get(key);
            }
            if (template == null) return missing(key);
            Object[] args = new Object[extra.length + 1];
            args[0] = n;
            System.arraycopy(extra, 0, args, 1, extra.length);
            return format(lang, template, args);
        };
    }

    public static void send(Player p, String key, Object... args) {
        p.sendMessage(get(p, key, args));
    }

    public static void send(Player p, Text text) {
        p.sendMessage(get(p, text));
    }

    public static void sendAll(String key, Object... args) {
        sendEach(Groups.player.copy(), text(key, args));
    }

    public static void sendTeam(Team team, String key, Object... args) {
        sendEach(Groups.player.copy().select(p -> p.team() == team), text(key, args));
    }

    private static void sendEach(Seq<Player> players, Text text) {
        ObjectMap<String, String> done = new ObjectMap<>();
        for (Player p : players) {
            String lang = lang(p);
            String message = done.get(lang);
            if (message == null) done.put(lang, message = text.in(lang));
            p.sendMessage(message);
        }
    }

    public static String format(String lang, String template, Object... args) {
        if (args == null || args.length == 0 || template.indexOf('{') < 0) return template;
        StringBuilder out = new StringBuilder(template.length() + 32);
        int i = 0, length = template.length();
        while (i < length) {
            char c = template.charAt(i);
            if (c == '{') {
                int j = i + 1, index = 0;
                while (j < length && j - i <= 3 && Character.isDigit(template.charAt(j))) index = index * 10 + (template.charAt(j++) - '0');
                if (j > i + 1 && j < length && template.charAt(j) == '}' && index < args.length) {
                    Object arg = args[index];
                    out.append(arg instanceof Text t ? t.in(lang) : String.valueOf(arg));
                    i = j + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static String missing(String key) {
        if (warned.add(key)) Log.warn("[@] No text for '@' in bundles/bundle.properties", pluginId, key);
        return "???" + key + "???";
    }

    public static Seq<String> languages() {
        return langs.orderedKeys().copy();
    }

    public static String languageName(String lang) {
        String name = own(lang, "language.name");
        return name == null ? lang : name;
    }

    public static String match(String locale) {
        if (locale == null || locale.isEmpty()) return null;
        String full = locale.replace('-', '_');
        String language = full.contains("_") ? full.substring(0, full.indexOf('_')) : full;
        for (String code : langs.keys()) if (code.equalsIgnoreCase(full)) return code;
        for (String code : langs.keys()) if (code.equalsIgnoreCase(language)) return code;
        for (String code : langs.keys()) {
            if (code.toLowerCase(Locale.ROOT).startsWith(language.toLowerCase(Locale.ROOT) + "_")) return code;
        }
        return null;
    }

    public static String findLanguage(String input) {
        if (input == null) return null;
        String code = match(input.trim());
        if (code != null) return code;
        for (String l : langs.keys()) if (languageName(l).equalsIgnoreCase(input.trim())) return l;
        return null;
    }

    public static String picked(Player p) {
        String l = chosen.get(p.uuid());
        return l != null && langs.containsKey(l) ? l : null;
    }

    public static void pick(Player p, String lang) {
        if (lang == null) chosen.remove(p.uuid());
        else chosen.put(p.uuid(), lang);
        saveChoices();
    }

    public static String serverDefault() {
        return serverDefault;
    }

    public static void setServerDefault(String lang) {
        serverDefault = lang;
        Core.settings.put(pluginId + "-default-language", lang);
        Core.settings.forceSave();
    }

    private static String parent(String lang) {
        if (BASE.equals(lang)) return null;
        int split = lang.lastIndexOf('_');
        return split > 0 ? lang.substring(0, split) : BASE;
    }

    static String category(String lang, long n) {
        n = Math.abs(n);
        long mod10 = n % 10, mod100 = n % 100;
        boolean fewEnding = mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14);
        return switch (languagePart(lang)) {
            case "ru", "uk", "be" -> mod10 == 1 && mod100 != 11 ? "one" : fewEnding ? "few" : "many";
            case "pl" -> n == 1 ? "one" : fewEnding ? "few" : "many";
            case "cs", "sk" -> n == 1 ? "one" : n >= 2 && n <= 4 ? "few" : "other";
            case "fr", "pt" -> n <= 1 ? "one" : "other";
            case "ja", "zh", "ko", "vi", "th", "id", "in", "ms" -> "other";
            default -> n == 1 ? "one" : "other";
        };
    }

    static String[] categories(String lang) {
        return switch (languagePart(lang)) {
            case "ru", "uk", "be", "pl" -> new String[]{"one", "few", "many"};
            case "cs", "sk" -> new String[]{"one", "few", "other"};
            case "ja", "zh", "ko", "vi", "th", "id", "in", "ms" -> new String[]{"other"};
            default -> new String[]{"one", "other"};
        };
    }

    private static String languagePart(String lang) {
        String l = lang.toLowerCase(Locale.ROOT);
        int split = l.indexOf('_');
        return split > 0 ? l.substring(0, split) : l;
    }

    public static void reload() {
        langs.clear();
        sources.clear();
        warned.clear();
        baseText = "";
        readJar();
        readOverrides();
        if (!langs.containsKey(BASE)) {
            Log.err("[@] bundles/bundle.properties is missing: the jar was built without the bundles folder. Players will see text keys.", pluginId);
            langs.put(BASE, new ObjectMap<>());
        }
        // base language first, then the rest by code
        Seq<String> order = langs.orderedKeys();
        order.remove(BASE);
        order.sort();
        order.insert(0, BASE);
        if (dir != null && !langs.containsKey(serverDefault)) serverDefault = BASE;
        Log.info("[@] Languages: @", pluginId, order.toString(", "));
    }

    private static void readJar() {
        try {
            URL location = Bundle.class.getProtectionDomain().getCodeSource().getLocation();
            File source = new File(location.toURI());
            if (source.isDirectory()) {
                File[] files = new File(source, "bundles").listFiles();
                if (files == null) return;
                for (File f : files) {
                    try (InputStream in = new FileInputStream(f)) {
                        read(f.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8), "jar", true);
                    }
                }
            } else {
                try (ZipFile zip = new ZipFile(source)) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("bundles/") || name.indexOf('/', 8) >= 0) continue;
                        try (InputStream in = zip.getInputStream(entry)) {
                            read(name.substring(8), new String(in.readAllBytes(), StandardCharsets.UTF_8), "jar", true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.err("[" + pluginId + "] Can't read bundles from the plugin jar", e);
        }
    }

    private static void readOverrides() {
        if (dir == null) return;
        Fi folder = dir.child("bundles");
        folder.mkdirs();
        Fi readme = folder.child("README.txt");
        if (!readme.exists()) {
            readme.writeString("""
                Files here override the plugin's texts key by key, and can add new languages.
                Names: bundle.properties (English), bundle_ru.properties, bundle_<code>.properties. UTF-8.
                Edit, then type "bundles reload" in the server console. "bundles export <code>" writes a
                translation template with every key to the export folder next to this one.
                """, false, "UTF-8");
        }
        for (Fi file : folder.list()) {
            if (!file.isDirectory()) read(file.name(), file.readString("UTF-8"), "config", false);
        }
    }

    private static void read(String fileName, String content, String where, boolean fromJar) {
        Matcher m = FILE.matcher(fileName);
        if (!m.matches()) return;
        String code = m.group(1) == null ? BASE : m.group(1);
        if (content.startsWith("﻿")) content = content.substring(1);
        Properties props = new Properties();
        try {
            props.load(new StringReader(content));
        } catch (Exception e) {
            Log.err("[" + pluginId + "] Can't read " + where + "/" + fileName, e);
            return;
        }
        ObjectMap<String, String> texts = langs.get(code);
        if (texts == null) langs.put(code, texts = new ObjectMap<>());
        for (String key : props.stringPropertyNames()) texts.put(key, props.getProperty(key));
        String before = sources.get(code);
        sources.put(code, before == null ? where : before + " + " + where);
        if (fromJar && code.equals(BASE)) baseText = content;
    }

    private static Fi choicesFile() {
        return dir.child("player-languages.tsv");
    }

    private static void loadChoices() {
        chosen.clear();
        Fi file = choicesFile();
        if (!file.exists()) return;
        for (String line : file.readString("UTF-8").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) chosen.put(line.substring(0, tab), line.substring(tab + 1).trim());
        }
    }

    private static void saveChoices() {
        StringBuilder out = new StringBuilder();
        chosen.each((uuid, lang) -> out.append(uuid).append('\t').append(lang).append('\n'));
        choicesFile().writeString(out.toString(), false, "UTF-8");
    }

    public static Seq<String> summary() {
        Seq<String> out = new Seq<>();
        for (String lang : langs.orderedKeys()) {
            int total = required(lang).size;
            int missing = missingKeys(lang).size;
            int percent = total == 0 ? 100 : Math.round(100f * (total - missing) / total);
            out.add(String.format("%-6s %-12s %3d%%  %3d missing  (%s)%s", lang, languageName(lang), percent, missing,
                sources.get(lang, "?"), lang.equals(serverDefault) ? "  <- default" : ""));
        }
        return out;
    }

    public static Seq<String> check() {
        Seq<String> out = new Seq<>();
        ObjectMap<String, String> base = langs.get(BASE);
        for (String key : duplicateKeys(baseText)) out.add("en: key '" + key + "' is written twice in bundle.properties");
        for (String group : pluralGroups()) {
            if (!base.containsKey(group + ".one") || !base.containsKey(group + ".other")) {
                out.add("en: plural '" + group + "' needs both " + group + ".one and " + group + ".other");
            }
        }
        for (String lang : langs.orderedKeys()) {
            if (lang.equals(BASE)) continue;
            ObjectMap<String, String> texts = langs.get(lang);
            Seq<String> missing = missingKeys(lang);
            if (missing.size > 10) {
                Seq<String> first = missing.copy();
                first.truncate(10);
                out.add(lang + ": " + missing.size + " missing, e.g. " + first.toString(", ") + " - \"bundles export " + lang + "\" lists them all");
            } else if (!missing.isEmpty()) {
                out.add(lang + ": " + missing.size + " missing: " + missing.toString(", "));
            }
            for (String key : texts.keys().toSeq().sort()) {
                String original = baseFor(key);
                if (original == null) {
                    if (!optional(key)) out.add(lang + ": unknown key '" + key + "' (renamed or removed in English?)");
                    continue;
                }
                ObjectSet<String> mine = placeholders(texts.get(key)), theirs = placeholders(original);
                boolean plural = isPluralVariant(key);
                for (String p : mine) {
                    if (!theirs.contains(p)) out.add(lang + ": '" + key + "' uses " + p + ", English has no such argument");
                }
                if (!plural) {
                    for (String p : theirs) {
                        if (!mine.contains(p)) out.add(lang + ": '" + key + "' doesn't show " + p);
                    }
                }
            }
        }
        return out;
    }

    private static Seq<String> required(String lang) {
        Seq<String> out = new Seq<>();
        ObjectMap<String, String> base = langs.get(BASE);
        if (base == null) return out;
        ObjectSet<String> groups = pluralGroups();
        for (String key : base.keys()) {
            if (optional(key) || isPluralVariant(key)) continue;
            out.add(key);
        }
        for (String group : groups) {
            for (String category : categories(lang)) out.add(group + "." + category);
        }
        return out.sort();
    }

    private static Seq<String> missingKeys(String lang) {
        ObjectMap<String, String> texts = langs.get(lang);
        return required(lang).select(key -> texts == null || !texts.containsKey(key));
    }

    private static ObjectSet<String> pluralGroups() {
        ObjectSet<String> out = new ObjectSet<>();
        ObjectMap<String, String> base = langs.get(BASE);
        if (base == null) return out;
        for (String key : base.keys()) {
            String group = groupOf(key);
            if (group != null && base.containsKey(group + ".other")) out.add(group);
        }
        return out;
    }

    private static String groupOf(String key) {
        int dot = key.lastIndexOf('.');
        if (dot <= 0) return null;
        String last = key.substring(dot + 1);
        for (String c : CATEGORIES) if (c.equals(last)) return key.substring(0, dot);
        return null;
    }

    private static boolean isPluralVariant(String key) {
        String group = groupOf(key);
        return group != null && langs.get(BASE).containsKey(group + ".other");
    }

    private static String baseFor(String key) {
        ObjectMap<String, String> base = langs.get(BASE);
        String value = base.get(key);
        if (value != null || !isPluralVariant(key)) return value;
        String group = groupOf(key);
        StringBuilder all = new StringBuilder();
        for (String c : CATEGORIES) {
            String v = base.get(group + "." + c);
            if (v != null) all.append(v).append('\n');
        }
        return all.toString();
    }

    private static boolean optional(String key) {
        return key.equals("language.name") || (key.startsWith("command.") && (key.endsWith(".aliases") || key.endsWith(".params")));
    }

    private static ObjectSet<String> placeholders(String text) {
        ObjectSet<String> out = new ObjectSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) out.add(m.group());
        return out;
    }

    public static Fi export(String lang) {
        ObjectMap<String, String> base = langs.get(BASE);
        ObjectMap<String, String> texts = langs.get(lang, new ObjectMap<>());
        StringBuilder out = new StringBuilder();
        out.append("# ").append(pluginId).append(" texts: ").append(languageName(lang)).append(" (").append(lang).append(")\n")
            .append("# Lines like \"#key = text\" are not translated yet: translate the text and remove the '#'.\n")
            .append("# Plural forms here: ").append(String.join(", ", categories(lang))).append('\n')
            .append("# Then put this file into config/").append(pluginId).append("/bundles/ and type \"bundles reload\".\n\n");

        ObjectSet<String> written = new ObjectSet<>();
        String text = baseText.isEmpty() ? fallbackBaseText(base) : baseText;
        for (String line : logicalLines(text)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                out.append(trimmed).append('\n');
                continue;
            }
            String key = keyOf(trimmed);
            if (key == null || written.contains(key)) continue;
            String group = isPluralVariant(key) ? groupOf(key) : null;
            if (group != null) {
                if (written.contains(group + ".*")) continue;
                written.add(group + ".*");
                for (String category : categories(lang)) {
                    String k = group + "." + category;
                    written.add(k);
                    String original = base.get(k, base.get(group + ".other"));
                    writeLine(out, k, texts.get(k), original);
                }
                for (String c : CATEGORIES) written.add(group + "." + c);
            } else {
                written.add(key);
                writeLine(out, key, texts.get(key), base.get(key));
            }
        }
        Seq<String> rest = texts.keys().toSeq().select(k -> !written.contains(k)).sort();
        if (!rest.isEmpty()) {
            out.append("\n# Only in this language\n");
            for (String k : rest) writeLine(out, k, texts.get(k), null);
        }

        Fi file = dir.child("export").child(lang.equals(BASE) ? "bundle.properties" : "bundle_" + lang + ".properties");
        file.writeString(out.toString(), false, "UTF-8");
        return file;
    }

    private static void writeLine(StringBuilder out, String key, String value, String original) {
        if (value != null) out.append(key).append(" = ").append(escape(value)).append('\n');
        else out.append('#').append(key).append(" = ").append(escape(original == null ? "" : original)).append('\n');
    }

    private static String fallbackBaseText(ObjectMap<String, String> base) {
        StringBuilder out = new StringBuilder();
        for (String key : base.keys().toSeq().sort()) out.append(key).append(" = x\n");
        return out.toString();
    }

    private static Seq<String> logicalLines(String text) {
        Seq<String> out = new Seq<>();
        StringBuilder current = null;
        for (String raw : text.split("\r?\n", -1)) {
            String line = current == null ? raw : raw.stripLeading();
            int slashes = 0;
            for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) slashes++;
            boolean continues = slashes % 2 == 1 && !(current == null && line.trim().startsWith("#"));
            if (continues) line = line.substring(0, line.length() - 1);
            if (current == null) current = new StringBuilder(line);
            else current.append(line);
            if (!continues) {
                out.add(current.toString());
                current = null;
            }
        }
        if (current != null) out.add(current.toString());
        return out;
    }

    private static String keyOf(String line) {
        int end = 0;
        while (end < line.length() && "=: \t".indexOf(line.charAt(end)) < 0) end++;
        return end == 0 ? null : line.substring(0, end);
    }

    private static Seq<String> duplicateKeys(String text) {
        ObjectSet<String> seen = new ObjectSet<>();
        Seq<String> out = new Seq<>();
        for (String line : logicalLines(text)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) continue;
            String key = keyOf(trimmed);
            if (key != null && !seen.add(key)) out.add(key);
        }
        return out;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\t' -> out.append("\\t");
                case ' ' -> out.append(i == 0 ? "\\ " : " ");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
