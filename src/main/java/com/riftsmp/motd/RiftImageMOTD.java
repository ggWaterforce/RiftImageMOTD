package com.riftsmp.motd;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.object.ObjectContents;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RiftImageMOTD extends JavaPlugin implements Listener {

    private static final int COLS = 33;
    private static final int ROWS = 2;
    private static final int TILE_SIZE = 8;
    private static final int EXPECTED_W = COLS * TILE_SIZE; // 264
    private static final int EXPECTED_H = ROWS * TILE_SIZE; // 16

    private final AtomicBoolean generating = new AtomicBoolean(false);
    private volatile Component cachedImageMotd;
    private volatile Component cachedFallbackMotd = Component.empty();
    private volatile int minProtocol = 773;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadMotdCache();
        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onServerListPing(PaperServerListPingEvent event) {
        Component img = this.cachedImageMotd;
        if (img != null && event.getClient().getProtocolVersion() >= this.minProtocol) {
            event.motd(img);
        } else {
            event.motd(this.cachedFallbackMotd);
        }
    }

    private void reloadMotdCache() {
        reloadConfig();
        this.minProtocol = getConfig().getInt("min-protocol", 773);

        String fallbackRaw = getConfig().getString("fallback-motd", "RIFT SMP").trim();
        this.cachedFallbackMotd = MiniMessage.miniMessage().deserialize(fallbackRaw);

        File texFile = new File(getDataFolder(), "textures.yml");
        if (!texFile.exists()) {
            this.cachedImageMotd = null;
            return;
        }

        YamlConfiguration texCfg = YamlConfiguration.loadConfiguration(texFile);
        TextComponent.Builder builder = Component.text();

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = row * COLS + col;
                String val = texCfg.getString("tiles." + idx + ".value");
                String sig = texCfg.getString("tiles." + idx + ".signature");
                if (val == null || sig == null) {
                    getLogger().warning("Incomplete textures.yml at tile " + idx + ". Run /riftmotd generate.");
                    this.cachedImageMotd = null;
                    return;
                }
                PlayerProfile profile = Bukkit.createProfile(UUID.randomUUID());
                profile.setProperty(new ProfileProperty("textures", val, sig));
                builder.append(Component.object(ObjectContents.playerHead(profile)));
            }
            if (row == 0) {
                builder.append(Component.newline());
            }
        }

        this.cachedImageMotd = builder.build();
        getLogger().info("Loaded cached Image MOTD (66 tiles).");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /riftmotd <generate|reload>"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadMotdCache();
            sender.sendMessage(Component.text("RiftImageMOTD reloaded."));
            return true;
        }

        if (args[0].equalsIgnoreCase("generate")) {
            if (!generating.compareAndSet(false, true)) {
                sender.sendMessage(Component.text("Generation is already running!"));
                return true;
            }
            String imageName = getConfig().getString("image-file", "banner.png");
            String apiKey = getConfig().getString("mineskin-api-key", "").trim();
            File imgFile = new File(getDataFolder(), imageName);

            if (!imgFile.exists()) {
                generating.set(false);
                sender.sendMessage(Component.text("File not found: plugins/RiftImageMOTD/" + imageName));
                return true;
            }

            sender.sendMessage(Component.text("Generating Image MOTD in the background... watch console for progress."));
            Thread.startVirtualThread(() -> generateBanner(sender, imgFile, apiKey));
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("generate", "reload");
        }
        return List.of();
    }

    private void generateBanner(CommandSender sender, File imgFile, String apiKey) {
        try {
            BufferedImage raw = ImageIO.read(imgFile);
            if (raw == null) {
                notifySender(sender, "Could not read PNG file.");
                return;
            }

            // Auto-scale to 264x16 if needed
            BufferedImage banner = new BufferedImage(EXPECTED_W, EXPECTED_H, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = banner.createGraphics();
            g.drawImage(raw, 0, 0, EXPECTED_W, EXPECTED_H, null);
            g.dispose();

            HttpClient http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();

            Map<String, String[]> dedupCache = new HashMap<>();
            YamlConfiguration out = new YamlConfiguration();
            int uploaded = 0;

            for (int row = 0; row < ROWS; row++) {
                for (int col = 0; col < COLS; col++) {
                    int idx = row * COLS + col;
                    byte[] skinPng = buildSkinTile(banner, col * TILE_SIZE, row * TILE_SIZE);
                    String hash = sha256(skinPng);

                    String[] tex = dedupCache.get(hash);
                    if (tex == null) {
                        tex = uploadToMineSkin(http, skinPng, apiKey);
                        dedupCache.put(hash, tex);
                        uploaded++;
                        if (uploaded % 5 == 0) {
                            getLogger().info("Uploaded " + uploaded + " unique tiles (tile " + (idx + 1) + "/66)...");
                        }
                    }

                    out.set("tiles." + idx + ".value", tex[0]);
                    out.set("tiles." + idx + ".signature", tex[1]);
                }
            }

            out.save(new File(getDataFolder(), "textures.yml"));
            reloadMotdCache();
            notifySender(sender, "Done! Uploaded " + uploaded + " unique tiles and applied your Image MOTD.");
        } catch (Exception e) {
            getLogger().severe("Failed to generate Image MOTD: " + e.getMessage());
            notifySender(sender, "Generation failed: " + e.getMessage());
        } finally {
            generating.set(false);
        }
    }

    private byte[] buildSkinTile(BufferedImage banner, int srcX, int srcY) throws Exception {
        // Place the 8x8 tile on the player head face (x: 8..15, y: 8..15) of a 64x64 transparent skin
        BufferedImage skin = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = skin.createGraphics();
        g.drawImage(banner, 8, 8, 16, 16, srcX, srcY, srcX + TILE_SIZE, srcY + TILE_SIZE, null);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream(512);
        ImageIO.write(skin, "png", baos);
        return baos.toByteArray();
    }

    private String[] uploadToMineSkin(HttpClient http, byte[] pngBytes, String apiKey) throws Exception {
        String dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes);
        JsonObject payload = new JsonObject();
        payload.addProperty("url", dataUrl);
        payload.addProperty("visibility", "unlisted");
        payload.addProperty("variant", "classic");

        for (int attempt = 1; attempt <= 4; attempt++) {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mineskin.org/v2/generate"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "RiftImageMOTD/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));

            if (!apiKey.isEmpty()) {
                req.header("Authorization", "Bearer " + apiKey);
            }

            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 429) {
                Thread.sleep(5000L * attempt);
                continue;
            }
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new RuntimeException("MineSkin HTTP " + res.statusCode() + ": " + res.body());
            }

            JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
            JsonObject data = json.getAsJsonObject("skin")
                    .getAsJsonObject("texture")
                    .getAsJsonObject("data");

            String value = data.get("value").getAsString();
            String signature = data.get("signature").getAsString();

            // Respect MineSkin rate-limit pacing
            long delayMs = apiKey.isEmpty() ? 3500L : 1500L;
            if (json.has("rateLimit") && json.getAsJsonObject("rateLimit").has("delay")) {
                JsonObject delay = json.getAsJsonObject("rateLimit").getAsJsonObject("delay");
                if (delay.has("millis")) {
                    delayMs = Math.max(delayMs, delay.get("millis").getAsLong());
                }
            }
            Thread.sleep(delayMs);

            return new String[]{value, signature};
        }

        throw new RuntimeException("MineSkin rate-limited after 4 retries. Add a free API key in config.yml.");
    }

    private String sha256(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        return Base64.getEncoder().encodeToString(digest);
    }

    private void notifySender(CommandSender sender, String msg) {
        Bukkit.getGlobalRegionScheduler().execute(this, () -> sender.sendMessage(Component.text("[RiftImageMOTD] " + msg)));
    }
}
