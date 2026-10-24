package de.example.ancientreminder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Ancient Reminder
 * Oeffne das Menue im Spiel mit dem Chat-Befehl:  /ancient
 */
public class AncientReminderClient implements ClientModInitializer {

    // ------------------------------------------------------------------
    //  Was gesucht werden kann (im Menue an/aus schaltbar)
    // ------------------------------------------------------------------
    enum Target {
        ANCIENT_DEBRIS("Ancient Debris", 0xFF8A00, Items.ANCIENT_DEBRIS, true, Blocks.ANCIENT_DEBRIS),
        GILDED_BLACKSTONE("Gilded Blackstone", 0xFFD700, null, false, Blocks.GILDED_BLACKSTONE),
        NETHER_GOLD("Nether Gold Ore", 0xF5C542, null, false, Blocks.NETHER_GOLD_ORE),
        NETHER_QUARTZ("Nether Quartz Ore", 0xEEEEEE, null, false, Blocks.NETHER_QUARTZ_ORE),
        DIAMOND("Diamond Ore", 0x33E0E0, Items.DIAMOND, false, Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
        EMERALD("Emerald Ore", 0x22CC55, Items.EMERALD, false, Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
        GOLD("Gold Ore", 0xFFCC00, Items.RAW_GOLD, false, Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
        IRON("Iron Ore", 0xD8AF93, Items.RAW_IRON, false, Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE);

        final String label;
        final int color;
        final Item item;          // optional: Item, das am Boden liegen kann
        final Block[] blocks;
        final boolean defaultEnabled;
        boolean enabled;
        DustParticleEffect particle;

        Target(String label, int color, Item item, boolean defaultEnabled, Block... blocks) {
            this.label = label;
            this.color = color;
            this.item = item;
            this.defaultEnabled = defaultEnabled;
            this.enabled = defaultEnabled;
            this.blocks = blocks;
        }

        DustParticleEffect particle() {
            if (particle == null) particle = new DustParticleEffect(color, 1.0f);
            return particle;
        }
    }

    // ------------------------------------------------------------------
    //  Einstellungen (werden in config/ancientreminder.properties gespeichert)
    // ------------------------------------------------------------------
    static final int[] RADII = {16, 24, 32, 40};
    static int radius = 32;
    static boolean tracer = true;
    static boolean sound = true;
    static boolean message = true;

    static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("ancientreminder.properties");
    }

    static void loadConfig() {
        Path f = configFile();
        if (!Files.exists(f)) return;
        try (InputStream in = Files.newInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            radius = Integer.parseInt(p.getProperty("radius", "32"));
            tracer = Boolean.parseBoolean(p.getProperty("tracer", "true"));
            sound = Boolean.parseBoolean(p.getProperty("sound", "true"));
            message = Boolean.parseBoolean(p.getProperty("message", "true"));
            for (Target t : Target.values()) {
                t.enabled = Boolean.parseBoolean(p.getProperty("target." + t.name(), String.valueOf(t.defaultEnabled)));
            }
        } catch (Exception ignored) {
        }
    }

    static void saveConfig() {
        try (OutputStream out = Files.newOutputStream(configFile())) {
            Properties p = new Properties();
            p.setProperty("radius", String.valueOf(radius));
            p.setProperty("tracer", String.valueOf(tracer));
            p.setProperty("sound", String.valueOf(sound));
            p.setProperty("message", String.valueOf(message));
            for (Target t : Target.values()) {
                p.setProperty("target." + t.name(), String.valueOf(t.enabled));
            }
            p.store(out, "Ancient Reminder");
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    //  Mod-Logik
    // ------------------------------------------------------------------
    private static final int SCAN_INTERVAL = 30;
    private static final int TRACER_INTERVAL = 4;
    private static final int MAX_TRACERS = 3;
    private static final double TRACER_STEP = 0.75;

    private record Hit(Vec3d pos, Target target) {}

    private int scanCounter = 0;
    private int tracerCounter = 0;
    private Set<Long> known = new HashSet<>();
    private List<Hit> tracerTargets = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);

        // Menue oeffnen mit /ancient
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("ancient").executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.send(() -> mc.setScreen(new ConfigScreen()));
                    return 1;
                })));
    }

    private void onTick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            known.clear();
            tracerTargets.clear();
            return;
        }
        if (++scanCounter >= SCAN_INTERVAL) {
            scanCounter = 0;
            scan(player, world);
        }
        if (++tracerCounter >= TRACER_INTERVAL) {
            tracerCounter = 0;
            if (tracer) drawTracers(player, world);
        }
    }

    private void scan(ClientPlayerEntity player, ClientWorld world) {
        Map<Block, Target> blockMap = new HashMap<>();
        Map<Item, Target> itemMap = new HashMap<>();
        for (Target t : Target.values()) {
            if (!t.enabled) continue;
            for (Block b : t.blocks) blockMap.put(b, t);
            if (t.item != null) itemMap.put(t.item, t);
        }
        if (blockMap.isEmpty()) {
            known.clear();
            tracerTargets.clear();
            return;
        }

        Set<Long> current = new HashSet<>();
        List<Hit> found = new ArrayList<>();
        BlockPos center = player.getBlockPos();
        Vec3d playerPos = player.getPos();

        Hit closestNew = null;
        double closestDist = Double.MAX_VALUE;

        // 1) Offen liegende Bloecke
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    pos.set(center.getX() + x, center.getY() + y, center.getZ() + z);
                    Target t = blockMap.get(world.getBlockState(pos).getBlock());
                    if (t == null) continue;
                    if (!isExposed(world, pos)) continue;

                    long key = pos.asLong();
                    current.add(key);
                    Hit hit = new Hit(Vec3d.ofCenter(pos), t);
                    found.add(hit);

                    if (!known.contains(key)) {
                        double d = hit.pos().squaredDistanceTo(playerPos);
                        if (d < closestDist) {
                            closestDist = d;
                            closestNew = hit;
                        }
                    }
                }
            }
        }

        // 2) Items am Boden
        if (!itemMap.isEmpty()) {
            var box = player.getBoundingBox().expand(radius);
            for (ItemEntity item : world.getEntitiesByClass(ItemEntity.class, box,
                    e -> itemMap.containsKey(e.getStack().getItem()))) {
                Target t = itemMap.get(item.getStack().getItem());
                long key = item.getBlockPos().asLong() ^ 0x5DEECE66DL;
                current.add(key);
                Hit hit = new Hit(item.getPos().add(0, 0.25, 0), t);
                found.add(hit);

                if (!known.contains(key)) {
                    double d = hit.pos().squaredDistanceTo(playerPos);
                    if (d < closestDist) {
                        closestDist = d;
                        closestNew = hit;
                    }
                }
            }
        }

        if (closestNew != null) {
            if (message) {
                int meters = (int) Math.round(Math.sqrt(closestDist));
                Vec3d p = closestNew.pos();
                player.sendMessage(Text.literal(closestNew.target().label + " vergessen! ")
                        .formatted(Formatting.GOLD, Formatting.BOLD)
                        .append(Text.literal(meters + " m entfernt (" + (int) Math.floor(p.x) + " "
                                + (int) Math.floor(p.y) + " " + (int) Math.floor(p.z) + ")")
                                .formatted(Formatting.YELLOW)), true);
            }
            if (sound) player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 0.8f);
        }

        found.sort(Comparator.comparingDouble(h -> h.pos().squaredDistanceTo(playerPos)));
        tracerTargets = found.size() > MAX_TRACERS ? new ArrayList<>(found.subList(0, MAX_TRACERS)) : found;
        known = current;
    }

    private void drawTracers(ClientPlayerEntity player, ClientWorld world) {
        if (tracerTargets.isEmpty()) return;

        Vec3d start = player.getEyePos()
                .add(player.getRotationVec(1.0f).multiply(1.2))
                .add(0, -0.4, 0);

        for (Hit hit : tracerTargets) {
            Vec3d delta = hit.pos().subtract(start);
            double length = delta.length();
            if (length < 1.5) continue;
            Vec3d step = delta.normalize().multiply(TRACER_STEP);
            int count = (int) (length / TRACER_STEP);

            Vec3d p = start;
            for (int i = 0; i < count; i++) {
                p = p.add(step);
                world.addParticleClient(hit.target().particle(), p.x, p.y, p.z, 0, 0, 0);
            }
        }
    }

    /** "Ausgegraben" = mindestens eine Seite grenzt an Luft/nicht-solide Bloecke. */
    private static boolean isExposed(ClientWorld world, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockState neighbor = world.getBlockState(pos.offset(dir));
            if (neighbor.isAir() || !neighbor.isOpaque()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Das Menue
    // ------------------------------------------------------------------
    static class ConfigScreen extends Screen {

        ConfigScreen() {
            super(Text.literal("Ancient Reminder"));
        }

        private static Text onOff(String name, boolean value) {
            return Text.literal(name + ": ").append(Text.literal(value ? "AN" : "AUS")
                    .formatted(value ? Formatting.GREEN : Formatting.RED));
        }

        @Override
        protected void init() {
            int colW = 150, gap = 10;
            int left = this.width / 2 - colW - gap / 2;
            int right = this.width / 2 + gap / 2;

            // Auswahl, was gesucht wird
            Target[] all = Target.values();
            for (int i = 0; i < all.length; i++) {
                Target t = all[i];
                int x = (i % 2 == 0) ? left : right;
                int y = 40 + (i / 2) * 24;
                this.addDrawableChild(ButtonWidget.builder(onOff(t.label, t.enabled), b -> {
                    t.enabled = !t.enabled;
                    b.setMessage(onOff(t.label, t.enabled));
                    saveConfig();
                }).dimensions(x, y, colW, 20).build());
            }

            int y = 40 + ((all.length + 1) / 2) * 24 + 10;

            // Radius
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Radius: " + radius + " Bloecke"), b -> {
                int idx = 0;
                for (int i = 0; i < RADII.length; i++) if (RADII[i] == radius) idx = i;
                radius = RADII[(idx + 1) % RADII.length];
                b.setMessage(Text.literal("Radius: " + radius + " Bloecke"));
                saveConfig();
            }).dimensions(left, y, colW, 20).build());

            // Tracer
            this.addDrawableChild(ButtonWidget.builder(onOff("Tracer", tracer), b -> {
                tracer = !tracer;
                b.setMessage(onOff("Tracer", tracer));
                saveConfig();
            }).dimensions(right, y, colW, 20).build());

            y += 24;

            // Sound
            this.addDrawableChild(ButtonWidget.builder(onOff("Sound", sound), b -> {
                sound = !sound;
                b.setMessage(onOff("Sound", sound));
                saveConfig();
            }).dimensions(left, y, colW, 20).build());

            // Nachricht
            this.addDrawableChild(ButtonWidget.builder(onOff("Nachricht", message), b -> {
                message = !message;
                b.setMessage(onOff("Nachricht", message));
                saveConfig();
            }).dimensions(right, y, colW, 20).build());

            y += 34;

            // Fertig
            this.addDrawableChild(ButtonWidget.builder(Text.literal("Fertig"), b -> this.close())
                    .dimensions(this.width / 2 - 75, y, 150, 20).build());
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 15, 0xFFFFFFFF);
        }

        @Override
        public void close() {
            saveConfig();
            super.close();
        }
    }
}
