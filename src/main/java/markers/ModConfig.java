package markers;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;

public class ModConfig {
    private static final File CONFIG_FILE = FabricLoader.getInstance().getConfigDir().resolve("dmgmarkers.json").toFile();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public boolean damageDisplayEnabled = true;

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (CONFIG_FILE.exists()) {
            try (FileReader reader = new FileReader(CONFIG_FILE)) {
                instance = GSON.fromJson(reader, ModConfig.class);
                if (instance == null) {
                    instance = new ModConfig();
                }
            } catch (Exception e) {
                instance = new ModConfig();
            }
        } else {
            instance = new ModConfig();
            save();
        }
    }

    public static void save() {
        if (instance == null) {
            instance = new ModConfig();
        }
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileWriter writer = new FileWriter(CONFIG_FILE)) {
                GSON.toJson(instance, writer);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
