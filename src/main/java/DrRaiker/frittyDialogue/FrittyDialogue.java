Package Fritty.FrittyDialogue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FrittyDialogue 1.2.0
 * Діалог = один файл. Усі вузли (гілки) лежать у секції "dialogue".
 * Перший вузол - стартовий. next: <вузол> переходить до вузла, без next діалог закривається.
 */
public final class FrittyDialogue extends JavaPlugin implements Listener, TabExecutor {

    // ------------------------------------------------------------------ модель

    enum State { BUSY, TYPING, CHOOSING, CONTINUE }

    record Answer(String text, Vector offset, List<String> commands, String next) {}

    record Node(String id, String text, int delay, Vector offset, List<Answer> answers, String next,
                String location, String camera, Float scale) {}

    record Settings(boolean hidePlayers, boolean oneTime, boolean seeThrough, String location, String camera,
                    boolean smooth, String sound, float pitch, boolean perChar, int bgRgb, int bgAlpha,
                    int lineWidth, boolean slowness, boolean invisible, boolean saveStart, float textScale) {}

    record Dialogue(String name, Settings cfg, LinkedHashMap<String, Node> nodes) {}

    static final class Session {
        final Player player;
        final Dialogue dialogue;
        Node node;
        int selected;
        State state = State.BUSY;
        boolean closed;
        Location start;
        Location base;
        String camStr;
        float scale = 1f;
        TextDisplay text;
        final List<TextDisplay> answers = new ArrayList<>();
        BukkitTask typeTask;
        BukkitTask moveTask;
        BukkitTask barTask;

        Session(Player player, Dialogue dialogue) {
            this.player = player;
            this.dialogue = dialogue;
        }
    }

    // ------------------------------------------------------------------ стан

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();
    private static final Pattern TOKEN =
            Pattern.compile("&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR]|.", Pattern.DOTALL);
    private static final String NAME_REGEX = "[^/\\\\\\s.:*?\"<>|]+";

    private final Map<String, Dialogue> dialogues = new LinkedHashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private File dialoguesDir;
    private File dataFile;
    private YamlConfiguration data;

    // ------------------------------------------------------------------ життєвий цикл

    @Override
    public void onEnable() {
        FileConfiguration cfg = getConfig();
        cfg.addDefault("log-dialogue-registration", true);
        cfg.addDefault("messages.camera-location", "&7The camera position in the {name} has been changed.");
        cfg.addDefault("messages.example-updated", "&7Example furniture has been updated");
        cfg.addDefault("messages.dialogue-created", "&aDialogue &f{name}&a created from &f{from}&a.");
        cfg.addDefault("messages.reloaded", "&7Dialogues reloaded.");
        cfg.addDefault("actionbar.space-to-continue", "&ePress F to continue");
        cfg.addDefault("actionbar.space-to-skip", "&7Press F to skip");
        cfg.addDefault("actionbar.space-to-choose", "&eScroll to select, F to choose");
        cfg.addDefault("errors.player-not-found", "&cPlayer not found");
        cfg.addDefault("errors.dialogue-not-found", "&cDialogue not found");
        cfg.addDefault("errors.dialogue-exists", "&cDialogue with this name already exists");
        cfg.addDefault("errors.invalid-name", "&cInvalid name (no dots, spaces or slashes)");
        cfg.addDefault("errors.one-time", "&cYou have already seen this dialogue");
        cfg.addDefault("errors.only-player", "&cOnly a player can do this");
        cfg.addDefault("errors.usage",
                "&7/md start <dialogue> [player] | stop [player] | create <new> [copy-from] | reload | load-example | set-camera-location <dialogue>");
        cfg.options().copyDefaults(true);
        saveConfig();

        dialoguesDir = new File(getDataFolder(), "dialogues");
        if (!dialoguesDir.exists()) {
            dialoguesDir.mkdirs();
            writeExample();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);

        loadAll();

        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand pc = getCommand("fritty-dialogue");
        if (pc != null) {
            pc.setExecutor(this);
            pc.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (Session s : new ArrayList<>(sessions.values())) {
            stopSession(s, true);
        }
    }

    // ------------------------------------------------------------------ завантаження файлів

    private void loadAll() {
        dialogues.clear();
        File[] files = dialoguesDir.listFiles((dir, n) -> n.endsWith(".yml"));
        if (files == null) return;
        Arrays.sort(files);
        for (File f : files) loadFile(f);
    }

    private Dialogue loadFile(File f) {
        String name = f.getName().substring(0, f.getName().length() - 4);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection d = y.getConfigurationSection("dialogue");
        if (d == null) {
            getLogger().warning("[" + name + "] немає секції 'dialogue' - пропускаю");
            return null;
        }

        LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
        for (String id : d.getKeys(false)) {
            ConfigurationSection n = d.getConfigurationSection(id);
            if (n == null) continue;
            List<Answer> answers = new ArrayList<>();
            ConfigurationSection as = n.getConfigurationSection("answers");
            if (as != null) {
                for (String aid : as.getKeys(false)) {
                    ConfigurationSection a = as.getConfigurationSection(aid);
                    if (a == null) continue;
                    List<String> cmds = new ArrayList<>(a.getStringList("commands"));
                    if (a.isString("command")) cmds.add(a.getString("command"));
                    answers.add(new Answer(a.getString("text", ""), parseVector(a.get("offset")),
                            cmds, clean(a.getString("next"))));
                }
            }
            nodes.put(id, new Node(id, n.getString("text", ""), n.getInt("char-delay", 2),
                    parseVector(n.get("location-offset")), answers, clean(n.getString("next")),
                    clean(n.getString("location")), clean(n.getString("camera-location")),
                    n.isSet("text-scale") ? clampScale(n.getDouble("text-scale", 1.0)) : null));
        }

        // попередження про next на неіснуючий вузол
        for (Node n : nodes.values()) {
            if (n.next() != null && !nodes.containsKey(n.next())) {
                getLogger().warning("[" + name + "] вузол '" + n.id() + "': next '" + n.next() + "' не існує");
            }
            for (Answer a : n.answers()) {
                if (a.next() != null && !nodes.containsKey(a.next())) {
                    getLogger().warning("[" + name + "] вузол '" + n.id() + "', відповідь '" + a.text()
                            + "': next '" + a.next() + "' не існує");
                }
            }
        }

        Dialogue dialogue = new Dialogue(name, parseSettings(y.getConfigurationSection("config")), nodes);
        dialogues.put(name, dialogue);
        if (getConfig().getBoolean("log-dialogue-registration", true)) {
            getLogger().info("Діалог зареєстровано: " + name + " (вузлів: " + nodes.size() + ")");
        }
        return dialogue;
    }

    private Settings parseSettings(ConfigurationSection c) {
        if (c == null) c = new YamlConfiguration();
        String bg = c.getString("background-color", "#1e1f22").replace("#", "").trim();
        int rgb;
        try {
            rgb = Integer.parseInt(bg, 16);
        } catch (NumberFormatException e) {
            rgb = 0x1e1f22;
        }
        int alpha = Math.max(0, Math.min(255, c.getInt("background-alpha", 110)));
        return new Settings(
                c.getBoolean("hide-players", true),
                c.getBoolean("one-time", false),
                c.getBoolean("see-through-blocks", false),
                c.getString("location", "0 0 0 world"),
                c.getString("camera-location", "0 0 0 0 0 world"),
                c.getBoolean("smooth-teleport", true),
                c.getString("char-sound", "ui.button.click"),
                (float) c.getDouble("char-sound-pitch", 1.0),
                c.getBoolean("per-char", true),
                rgb, alpha,
                c.getInt("line-width", 200),
                c.getBoolean("use-slowness", true),
                c.getBoolean("make-invisible", true),
                c.getBoolean("save-start-location", true),
                clampScale(c.getDouble("text-scale", 1.0)));
    }

    private static float clampScale(double v) {
        return (float) Math.max(0.1, Math.min(10.0, v));
    }

    private static String clean(String s) {
        if (s == null) return null;
        s = s.trim();
        return s.isEmpty() ? null : s;
    }

    private static Vector parseVector(Object o) {
        double[] v = new double[3];
        if (o != null) {
            String[] t = o.toString().trim().split("\\s+");
            for (int i = 0; i < 3 && i < t.length; i++) {
                try {
                    v[i] = Double.parseDouble(t[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new Vector(v[0], v[1], v[2]);
    }

    /** "x y z world" або "x y z yaw pitch world" */
    private static Location parseLoc(String s) {
        String[] t = s.trim().split("\\s+");
        World w = Bukkit.getWorlds().get(0);
        int n = t.length;
        if (n > 0 && !isNumber(t[n - 1])) {
            World x = Bukkit.getWorld(t[n - 1]);
            if (x != null) w = x;
            n--;
        }
        double[] v = new double[5];
        for (int i = 0; i < 5 && i < n; i++) {
            try {
                v[i] = Double.parseDouble(t[i]);
            } catch (NumberFormatException ignored) {
            }
        }
        return new Location(w, v[0], v[1], v[2], (float) v[3], (float) v[4]);
    }

    private static boolean isNumber(String s) {
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ відображення

    private static Component render(String s) {
        return LEGACY.deserialize(s)
                .decoration(TextDecoration.ITALIC, false)
                .colorIfAbsent(NamedTextColor.WHITE);
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(s);
        while (m.find()) out.add(m.group());
        return out;
    }

    private static boolean isCode(String token) {
        return token.length() > 1 && token.charAt(0) == '&';
    }

    private TextDisplay spawnDisplay(Session s, Location loc, Component text) {
        Settings c = s.dialogue.cfg();
        Color bg = Color.fromARGB(c.bgAlpha(), (c.bgRgb() >> 16) & 255, (c.bgRgb() >> 8) & 255, c.bgRgb() & 255);
        TextDisplay td = loc.getWorld().spawn(loc, TextDisplay.class, d -> {
            d.setVisibleByDefault(false);
            d.setPersistent(false);
            d.setBillboard(Display.Billboard.CENTER);
            d.setLineWidth(c.lineWidth());
            d.setBackgroundColor(bg);
            d.setSeeThrough(c.seeThrough());
            d.text(text);
        });
        s.player.showEntity(this, td);
        return td;
    }

    private void setScale(TextDisplay td, float f, int ticks) {
        td.setInterpolationDelay(0);
        td.setInterpolationDuration(ticks);
        td.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                new Vector3f(f, f, f), new AxisAngle4f()));
    }

    private void select(Session s, int idx) {
        for (int i = 0; i < s.answers.size(); i++) {
            setScale(s.answers.get(i), i == idx ? s.scale * 1.2f : s.scale, 3);
        }
        s.selected = idx;
    }

    private void clearAnswers(Session s) {
        for (TextDisplay td : s.answers) td.remove();
        s.answers.clear();
    }

    private void sound(Player p, Settings c) {
        if (c.sound() == null || c.sound().isBlank()) return;
        p.playSound(p.getLocation(), c.sound(), SoundCategory.MASTER, 1f, c.pitch());
    }

    private void sendBar(Session s) {
        String key = switch (s.state) {
            case TYPING -> "actionbar.space-to-skip";
            case CHOOSING -> "actionbar.space-to-choose";
            case CONTINUE -> "actionbar.space-to-continue";
            default -> null;
        };
        if (key == null) return;
        s.player.sendActionBar(render(getConfig().getString(key, "")));
    }

    private static void cancel(BukkitTask t) {
        if (t != null) t.cancel();
    }

    // ------------------------------------------------------------------ логіка діалогу

    public boolean startDialogue(Dialogue d, Player p) {
        Settings c = d.cfg();
        if (c.oneTime() && isCompleted(p.getUniqueId(), d.name())) {
            msg(p, "errors.one-time");
            return false;
        }
        if (d.nodes().isEmpty()) return false;

        Location start = c.saveStart() ? p.getLocation().clone() : null;
        Session old = sessions.get(p.getUniqueId());
        if (old != null) {
            // діалог запущено з команди відповіді: лишаємо точку повернення
            if (old.start != null) start = old.start;
            stopSession(old, false);
        }

        Session s = new Session(p, d);
        s.start = start;
        s.base = parseLoc(c.location());
        sessions.put(p.getUniqueId(), s);

        if (c.invisible()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 20 * 60 * 60, 0, false, false, false));
        }
        if (c.slowness()) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 60 * 60, 10, false, false, false));
        }
        if (c.hidePlayers()) {
            for (Player o : Bukkit.getOnlinePlayers()) {
                if (!o.equals(p)) p.hidePlayer(this, o);
            }
        }

        s.text = spawnDisplay(s, s.base.clone(), Component.empty());
        s.barTask = Bukkit.getScheduler().runTaskTimer(this, () -> sendBar(s), 0L, 20L);

        // камеру і місце тексту для першого вузла виставляє showNode
        showNode(s, d.nodes().values().iterator().next());
        return true;
    }

    private void moveCamera(Session s, Location to, Runnable done) {
        Player p = s.player;
        Location from = p.getLocation();
        if (!s.dialogue.cfg().smooth() || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            p.teleport(to);
            done.run();
            return;
        }
        final int steps = 20;
        final int[] i = {0};
        s.moveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (s.closed) {
                cancel(s.moveTask);
                return;
            }
            i[0]++;
            double t = i[0] / (double) steps;
            t = t * t * (3 - 2 * t);
            float dyaw = ((to.getYaw() - from.getYaw() + 540f) % 360f) - 180f;
            Location l = new Location(to.getWorld(),
                    from.getX() + (to.getX() - from.getX()) * t,
                    from.getY() + (to.getY() - from.getY()) * t,
                    from.getZ() + (to.getZ() - from.getZ()) * t,
                    (float) (from.getYaw() + dyaw * t),
                    (float) (from.getPitch() + (to.getPitch() - from.getPitch()) * t));
            p.teleport(l);
            if (i[0] >= steps) {
                cancel(s.moveTask);
                s.moveTask = null;
                p.teleport(to);
                done.run();
            }
        }, 1L, 1L);
    }

    /** Показує вузол: виставляє місце тексту, камеру (якщо змінилась), розмір шрифту і запускає друк. */
    private void showNode(Session s, Node node) {
        cancel(s.typeTask);
        s.typeTask = null;
        clearAnswers(s);
        s.node = node;
        s.selected = 0;
        s.state = State.BUSY;

        Settings c = s.dialogue.cfg();
        s.scale = node.scale() != null ? node.scale() : c.textScale();
        s.base = parseLoc(node.location() != null ? node.location() : c.location());
        placeText(s, s.base.clone().add(node.offset()));
        setScale(s.text, s.scale, 0);

        String cam = node.camera() != null ? node.camera() : c.camera();
        if (!cam.equals(s.camStr)) {
            s.camStr = cam;
            moveCamera(s, parseLoc(cam), () -> {
                if (!s.closed && s.node == node) typeNode(s, node);
            });
        } else {
            typeNode(s, node);
        }
    }

    private void placeText(Session s, Location loc) {
        s.text.text(Component.empty());
        if (s.text.getWorld().equals(loc.getWorld())) {
            s.text.teleport(loc);
        } else {
            s.text.remove();
            s.text = spawnDisplay(s, loc, Component.empty());
        }
    }

    private void typeNode(Session s, Node node) {
        if (s.closed) return;
        s.state = State.TYPING;
        Settings c = s.dialogue.cfg();
        List<String> tokens = tokenize(node.text());
        if (!c.perChar() || tokens.isEmpty()) {
            finishTyping(s);
            return;
        }

        StringBuilder sb = new StringBuilder();
        final int[] pos = {0};
        s.typeTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (s.closed || s.state != State.TYPING) return;
            while (pos[0] < tokens.size()) {
                String t = tokens.get(pos[0]++);
                sb.append(t);
                                        
