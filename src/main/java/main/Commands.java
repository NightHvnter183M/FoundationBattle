package main;

import arc.Events;
import arc.struct.ObjectMap;
import arc.struct.ObjectSet;
import arc.struct.OrderedMap;
import arc.struct.Seq;
import arc.util.CommandHandler;
import arc.util.CommandHandler.Command;
import arc.util.CommandHandler.CommandResponse;
import arc.util.CommandHandler.ResponseType;
import arc.util.Log;
import arc.util.Strings;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Player;

import java.util.Locale;

public final class Commands {
    public interface Runner {
        void run(Player player, String[] args);
    }

    private static final class Entry {
        final String name, params;
        final boolean admin;
        final Runner runner;
        final Seq<String> aliases = new Seq<>();
        final Seq<String> bundleAliases = new Seq<>();

        Entry(String name, String params, boolean admin, Runner runner) {
            this.name = name;
            this.params = params;
            this.admin = admin;
            this.runner = runner;
        }
    }

    private static final int PAGE_SIZE = 8;
    private static final ObjectSet<String> ADMIN_BUILTINS = ObjectSet.with("a");
    private static final String RU = "йцукенгшщзхъфывапролджэячсмитьбюё";
    private static final String EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,.`";
    private static CommandHandler handler;
    private static final OrderedMap<String, Entry> entries = new OrderedMap<>();
    private static final ObjectMap<String, Entry> names = new ObjectMap<>();
    private static final Seq<String> codeProblems = new Seq<>(), bundleProblems = new Seq<>();
    private static Player chatPlayer;
    private static String chatMessage;

    public static void init(CommandHandler commands) {
        handler = commands;
        handler.removeCommand("help");
        add("help", "[page]", Commands::help, "commands", "cmds");
        Vars.netServer.invalidHandler = Commands::invalid;
        Vars.netServer.admins.addChatFilter(Commands::layoutFilter);
        Events.on(EventType.PlayerChatEvent.class, e -> {
            chatPlayer = e.player;
            chatMessage = e.message;
        });
    }

    public static void add(String name, String params, Runner runner, String... aliases) {
        add(name, params, false, runner, aliases);
    }

    public static void admin(String name, String params, Runner runner, String... aliases) {
        add(name, params, true, runner, aliases);
    }

    private static void add(String name, String params, boolean admin, Runner runner, String... aliases) {
        Entry entry = new Entry(normalize(name), params, admin, runner);
        entries.put(entry.name, entry);
        register(entry, entry.name);
        for (String alias : aliases) {
            if (tryAlias(entry, alias, codeProblems)) entry.aliases.add(normalize(alias));
        }
        addBundleAliases(entry);
    }

    public static void refreshAliases() {
        for (Entry entry : entries.values()) {
            for (String alias : entry.bundleAliases) {
                handler.removeCommand(alias);
                names.remove(alias);
            }
            entry.bundleAliases.clear();
        }
        bundleProblems.clear();
        for (Entry entry : entries.values()) addBundleAliases(entry);
    }

    public static Seq<String> problems() {
        return codeProblems.copy().addAll(bundleProblems);
    }

    private static void addBundleAliases(Entry entry) {
        for (String lang : Bundle.languages()) {
            for (String alias : split(Bundle.own(lang, "command." + entry.name + ".aliases"))) {
                if (entry.aliases.contains(alias) || entry.bundleAliases.contains(alias)) continue;
                if (tryAlias(entry, alias, bundleProblems)) entry.bundleAliases.add(alias);
            }
        }
    }

    private static boolean tryAlias(Entry entry, String raw, Seq<String> problems) {
        String alias = normalize(raw);
        String problem = null;
        Entry owner = names.get(alias);
        if (alias.isEmpty() || alias.contains(" ")) problem = "'" + raw + "' can't be a command name";
        else if (owner == entry) return false;
        else if (owner != null) problem = "/" + alias + " is already a name of /" + owner.name;
        else if (find(alias) != null) problem = "/" + alias + " is already another command";
        if (problem != null) {
            String message = "Alias of /" + entry.name + " skipped: " + problem;
            problems.add(message);
            Log.warn("[Commands] @", message);
            return false;
        }
        register(entry, alias);
        return true;
    }

    private static void register(Entry entry, String name) {
        String description = Bundle.find(Bundle.BASE, "command." + entry.name + ".description");
        handler.<Player>register(name, entry.params, description == null ? "" : description, (args, player) -> {
            if (entry.admin && !player.admin()) {
                Bundle.send(player, "command.no-permission");
                return;
            }
            entry.runner.run(player, args);
        });
        names.put(name, entry);
    }

    private static void help(Player p, String[] args) {
        String lang = Bundle.lang(p);
        Seq<String> lines = new Seq<>();
        for (Entry entry : entries.values()) {
            if (entry.admin && !p.admin()) continue;
            lines.add(line(lang, entry.name, params(lang, entry.name, entry.params), shownAliases(lang, entry),
                description(lang, entry.name, "")));
        }
        // built-in commands and other plugins' commands
        for (Command command : handler.getCommandList()) {
            String name = command.text.toLowerCase(Locale.ROOT);
            if (names.containsKey(name) || (ADMIN_BUILTINS.contains(name) && !p.admin())) continue;
            lines.add(line(lang, command.text, params(lang, name, command.paramText), Seq.with(),
                description(lang, name, command.description)));
        }

        int pages = Math.max(1, (lines.size + PAGE_SIZE - 1) / PAGE_SIZE);
        int page = args.length > 0 ? Strings.parseInt(args[0], -1) : 1;
        if (page < 1 || page > pages) {
            Bundle.send(p, "help.bad-page", pages);
            return;
        }
        StringBuilder out = new StringBuilder(Bundle.get(lang, "help.title", page, pages));
        for (int i = (page - 1) * PAGE_SIZE; i < Math.min(page * PAGE_SIZE, lines.size); i++) out.append('\n').append(lines.get(i));
        if (page < pages) out.append('\n').append(Bundle.get(lang, "help.next", page + 1));
        p.sendMessage(out.toString());
    }

    private static String line(String lang, String name, String params, Seq<String> aliases, String description) {
        StringBuilder out = new StringBuilder("[orange]/").append(name);
        if (!aliases.isEmpty()) out.append("[lightgray] (/").append(aliases.toString(", /")).append(")");
        if (!params.isEmpty()) out.append("[white] ").append(params);
        if (!description.isEmpty()) out.append("[lightgray] - ").append(description);
        return out.toString();
    }

    private static String params(String lang, String name, String fallback) {
        String translated = Bundle.find(lang, "command." + name + ".params");
        return translated != null ? translated : fallback;
    }

    private static String description(String lang, String name, String fallback) {
        String translated = Bundle.find(lang, "command." + name + ".description");
        return translated != null ? translated : fallback;
    }

    private static Seq<String> shownAliases(String lang, Entry entry) {
        Seq<String> out = entry.aliases.copy();
        for (String l : Seq.with(lang, Bundle.BASE)) {
            for (String alias : split(Bundle.own(l, "command." + entry.name + ".aliases"))) {
                if (entry.bundleAliases.contains(alias) && !out.contains(alias)) out.add(alias);
            }
        }
        return out;
    }

    private static String invalid(Player p, CommandResponse response) {
        if (response.type == ResponseType.manyArguments || response.type == ResponseType.fewArguments) {
            return Bundle.get(p, response.type == ResponseType.manyArguments ? "command.too-many-args" : "command.too-few-args",
                response.command.text, response.command.paramText);
        }
        String typed = response.runCommand == null ? "" : response.runCommand;
        String prefix = handler.getPrefix();
        String rest = "";
        if (chatPlayer == p && chatMessage != null && chatMessage.startsWith(prefix + typed)) {
            rest = chatMessage.substring(prefix.length() + typed.length());
        }
        String fixed = understand(typed);
        if (fixed != null) {
            dispatch(p, fixed, rest);
            return null;
        }
        String guess = guess(p, typed);
        return guess == null ? Bundle.get(p, "command.unknown") : Bundle.get(p, "command.unknown-guess", guess);
    }

    private static String layoutFilter(Player p, String message) {
        if (message.length() < 3 || message.charAt(0) != '.' || !isCyrillic(message.charAt(1))) return message;
        int space = message.indexOf(' ');
        String word = message.substring(1, space < 0 ? message.length() : space).toLowerCase(Locale.ROOT);
        Command command = find(word); // a Cyrillic alias
        if (command == null) command = find(fromRussianLayout(word));
        if (command == null || command.text.length() < 2) return message;
        dispatch(p, command.text, space < 0 ? "" : message.substring(space));
        return null;
    }

    private static String understand(String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        for (String candidate : Seq.with(lower, fromRussianLayout(lower), toRussianLayout(lower))) {
            Command command = find(candidate);
            // the same lower-case name failed already, so trying it again would go in circles
            if (command != null && !(command.text.equals(typed) && typed.equals(lower))) return command.text;
        }
        return null;
    }

    private static void dispatch(Player p, String name, String rest) {
        String prefix = handler.getPrefix();
        CommandResponse response = handler.handleMessage(prefix + name + rest, p);
        // some servers store command names in lower case only
        String lower = name.toLowerCase(Locale.ROOT);
        if (response.type == ResponseType.unknownCommand && !lower.equals(name)) {
            response = handler.handleMessage(prefix + lower + rest, p);
        }
        if (response.type == ResponseType.unknownCommand) Bundle.send(p, "command.unknown");
        else if (response.type != ResponseType.valid) p.sendMessage(invalid(p, response));
    }

    private static String guess(Player p, String typed) {
        String lower = typed.toLowerCase(Locale.ROOT);
        Seq<String> inputs = Seq.with(lower);
        String latin = fromRussianLayout(lower);
        if (!latin.equals(lower)) inputs.add(latin);

        String best = null;
        int bestDistance = 3;
        for (Command command : handler.getCommandList()) {
            String name = command.text.toLowerCase(Locale.ROOT);
            Entry entry = names.get(name);
            // short names like /t or /j match almost anything
            if (name.length() < 3) continue;
            if ((entry != null && entry.admin || ADMIN_BUILTINS.contains(name)) && !p.admin()) continue;
            for (String input : inputs) {
                int distance = Strings.levenshtein(input, name);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = entry != null ? entry.name : command.text;
                }
            }
        }
        return best;
    }

    private static Command find(String name) {
        for (Command command : handler.getCommandList()) {
            if (command.text.equalsIgnoreCase(name)) return command;
        }
        return null;
    }

    public static String fromRussianLayout(String text) {
        return swap(text, RU, EN);
    }

    public static String toRussianLayout(String text) {
        return swap(text, EN, RU);
    }

    private static String swap(String text, String from, String to) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            int index = from.indexOf(Character.toLowerCase(c));
            out.append(index < 0 ? c : to.charAt(index));
        }
        return out.toString();
    }

    private static boolean isCyrillic(char c) {
        return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC;
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private static Seq<String> split(String list) {
        Seq<String> out = new Seq<>();
        if (list == null) return out;
        for (String part : list.split(",")) {
            String alias = normalize(part);
            if (!alias.isEmpty()) out.add(alias);
        }
        return out;
    }
}
