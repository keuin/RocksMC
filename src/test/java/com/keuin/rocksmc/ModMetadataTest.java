package com.keuin.rocksmc;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every mixin on disk is registered, and that the mod metadata is sane.
 *
 * <h2>Why this test exists</h2>
 *
 * <p>An unregistered mixin is the most dangerous silent failure this mod can have, and
 * nothing else detects it. Mixin only reads the class names listed in the JSON; a class
 * sitting unreferenced in the mixin package is never loaded, produces <b>no error, no
 * warning, at any log level</b>, and ships as dead weight. {@code "required": true}
 * does not help -- it means "fail if this config cannot be applied", not "fail on
 * unlisted classes" -- and {@code defaultRequire: 1} only validates injection points
 * inside mixins that were actually loaded.
 *
 * <p>The consequence for this mod specifically: if {@code RegionBasedStorageMixin} fell
 * off the list, the four seam methods would never be replaced and the server would run
 * <b>pure vanilla Anvil while logging "RocksDB backend ENABLED"</b>. Every guard would
 * be bypassed too. On a world already migrated, players would see terrain frozen at
 * import time and every session since would be written to {@code .mca} files the
 * database never sees. That is precisely the silent divergence the configuration
 * loader goes to great lengths to prevent, reachable by another route entirely.
 *
 * <p>The same class of bug -- a hand-maintained list that compiles cleanly while being
 * incomplete -- already shipped once here as a missing bundled Prometheus module, which
 * crashed servers started from the built jar. Lists of this kind get checked
 * mechanically rather than by care.
 */
class ModMetadataTest {

    private static final File MIXIN_SOURCE_DIR =
        new File("src/main/java/com/keuin/rocksmc/mixin");
    private static final File MIXIN_CONFIG =
        new File("src/main/resources/rocksmc.mixins.json");
    private static final File MOD_METADATA =
        new File("src/main/resources/fabric.mod.json");

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Extracts a JSON string array without a JSON library.
     *
     * <p>The test source set has no JSON dependency, and adding one to read two arrays
     * would be worse than a targeted regex. Deliberately strict: it matches the array
     * body then pulls quoted strings, so a malformed file fails the test rather than
     * silently yielding an empty list that would make every assertion below pass.
     */
    private static List<String> jsonStringArray(String json, String key) {
        Matcher array = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^]]*)]")
            .matcher(json);
        assertTrue(array.find(), "no \"" + key + "\" array found in the JSON");
        List<String> out = new ArrayList<>();
        Matcher item = Pattern.compile("\"([^\"]+)\"").matcher(array.group(1));
        while (item.find()) {
            out.add(item.group(1));
        }
        return out;
    }

    /** Mixin class names present as source files, without the .java suffix. */
    private static List<String> mixinClassesOnDisk() {
        File[] files = MIXIN_SOURCE_DIR.listFiles((d, n) -> n.endsWith(".java"));
        assertTrue(files != null && files.length > 0,
            "no mixin sources found in " + MIXIN_SOURCE_DIR.getAbsolutePath()
                + " -- has the package moved? This test would otherwise pass vacuously.");
        List<String> names = new ArrayList<>();
        for (File f : files) {
            names.add(f.getName().substring(0, f.getName().length() - ".java".length()));
        }
        Collections.sort(names);
        return names;
    }

    // ------------------------------------------------------------------- mixins

    /**
     * Every mixin source file must be listed in the config.
     *
     * <p>This is the assertion that matters. An unlisted mixin is invisible at runtime.
     */
    @Test
    void everyMixinOnDiskIsRegistered() throws IOException {
        List<String> onDisk = mixinClassesOnDisk();
        List<String> registered = jsonStringArray(read(MIXIN_CONFIG), "server");
        Collections.sort(registered);

        for (String name : onDisk) {
            assertTrue(registered.contains(name),
                name + " exists in the mixin package but is NOT listed in "
                    + MIXIN_CONFIG.getName() + ". It would ship in the jar, never be "
                    + "applied, and log nothing. Registered: " + registered);
        }
        assertEquals(onDisk, registered,
            "the mixin config and the mixin package must agree exactly");
    }

    /** A listed mixin that no longer exists would fail at load with a hard error. */
    @Test
    void everyRegisteredMixinExistsOnDisk() throws IOException {
        List<String> onDisk = mixinClassesOnDisk();
        for (String name : jsonStringArray(read(MIXIN_CONFIG), "server")) {
            assertTrue(onDisk.contains(name),
                name + " is listed in " + MIXIN_CONFIG.getName()
                    + " but has no source file. Mixin would fail to load the config.");
        }
    }

    /**
     * The config must keep the settings that make a broken injector loud.
     *
     * <p>{@code defaultRequire: 1} is what turns an injector that matches nothing into
     * a startup failure instead of a silently absent behaviour change. Dropping it
     * would make every seam in this mod fail quietly.
     */
    @Test
    void mixinConfigFailsLoudlyOnABrokenInjector() throws IOException {
        String config = read(MIXIN_CONFIG);
        assertTrue(config.contains("\"defaultRequire\": 1"),
            "defaultRequire must stay at 1 so an injector that matches nothing is a "
                + "startup failure rather than a silent no-op:\n" + config);
        assertTrue(config.contains("\"required\": true"), config);
        assertTrue(config.contains("\"package\": \"com.keuin.rocksmc.mixin\""), config);
    }

    /**
     * Mixins belong under {@code server}, since the mod is server-only.
     *
     * <p>A mixin listed under the environment-agnostic {@code mixins} key would also be
     * applied on a client, where the classes it targets may not exist.
     */
    @Test
    void mixinsAreDeclaredServerSide() throws IOException {
        String config = read(MIXIN_CONFIG);
        assertTrue(config.contains("\"server\""), config);
        assertFalse(config.matches("(?s).*\"client\"\\s*:\\s*\\[\\s*\"[^\"]+\".*"),
            "this mod is server-only; a client mixin list is a mistake:\n" + config);
    }

    // ---------------------------------------------------------------- metadata

    /** The mixin config must be referenced, or no mixin applies at all. */
    @Test
    void modMetadataReferencesTheMixinConfig() throws IOException {
        List<String> configs = jsonStringArray(read(MOD_METADATA), "mixins");
        assertTrue(configs.contains(MIXIN_CONFIG.getName()),
            "fabric.mod.json must list " + MIXIN_CONFIG.getName()
                + " or no mixin is applied and the mod silently does nothing: "
                + configs);
    }

    /**
     * The command API must be declared as a dependency.
     *
     * <p>Command registration goes through {@code CommandRegistrationCallback} because
     * vanilla rebuilds the dispatcher on every datapack reload. If the dependency were
     * missing, the mod would load and {@code /rocksmc} would be absent -- which is the
     * exact symptom already seen once in production.
     */
    @Test
    void modMetadataDependsOnTheCommandApi() throws IOException {
        String metadata = read(MOD_METADATA);
        assertTrue(metadata.contains("fabric-command-api-v1"),
            "fabric.mod.json must depend on fabric-command-api-v1, which supplies the "
                + "callback command registration relies on:\n" + metadata);
    }

    /** The entrypoint must name a class that exists, or Loader crashes at boot. */
    @Test
    void modMetadataEntrypointExists() throws IOException {
        List<String> entrypoints = jsonStringArray(read(MOD_METADATA), "server");
        assertEquals(1, entrypoints.size(), "expected exactly one server entrypoint");
        String entry = entrypoints.get(0);
        assertEquals("com.keuin.rocksmc.RocksMc", entry);
        assertTrue(Paths.get("src/main/java",
                entry.replace('.', '/') + ".java").toFile().isFile(),
            "entrypoint " + entry + " has no source file");
    }
}
