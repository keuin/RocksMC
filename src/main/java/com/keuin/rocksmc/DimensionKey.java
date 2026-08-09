package com.keuin.rocksmc;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dimension's stable identity, derived from its save directory.
 *
 * <h2>Why the directory, and not a {@code RegistryKey<World>}</h2>
 *
 * <p>The obvious source of identity is the registry key, but it is not reachable
 * from where we need it. {@code RegionBasedStorage} is constructed deep inside
 * {@code ThreadedAnvilChunkStorage}'s {@code super(...)} call, and the chain
 * ({@code VersionedChunkStorage} to {@code StorageIoWorker} to
 * {@code RegionBasedStorage}) passes only a {@code File}. Threading the key
 * through would mean changing those constructor signatures, which an access
 * widener cannot do -- it changes access modifiers, not signatures.
 *
 * <p>The alternatives all smuggle the value out of band: a {@code ThreadLocal}
 * set in an outer constructor, or an {@code @Redirect} on the construction site.
 * Both depend on <em>when and where</em> code runs, which is exactly what other
 * mods change. A mod that reworks chunk I/O to be asynchronous, or constructs a
 * storage instance outside our call stack, silently breaks them -- and a silent
 * break here means chunks written under the wrong dimension id.
 *
 * <p>The directory, by contrast, is already passed to us as a parameter.
 * {@code DimensionType.getSaveDirectory} is a total, stable function from
 * dimension to directory, so inverting it is deterministic and depends on nothing
 * but its input. Two dimensions cannot share a directory -- they would corrupt
 * each other in vanilla too -- so uniqueness comes for free.
 *
 * <h2>The layouts</h2>
 *
 * <p>Mirroring {@code DimensionType.getSaveDirectory} exactly:
 *
 * <pre>
 *   &lt;root&gt;/region                              -&gt; minecraft:overworld
 *   &lt;root&gt;/DIM-1/region                        -&gt; minecraft:the_nether
 *   &lt;root&gt;/DIM1/region                         -&gt; minecraft:the_end
 *   &lt;root&gt;/dimensions/&lt;namespace&gt;/&lt;path&gt;/region -&gt; &lt;namespace&gt;:&lt;path&gt;
 * </pre>
 *
 * <p>The same shapes occur with a {@code poi} leaf instead of {@code region}.
 * Anything else throws: an unrecognised layout must not silently collapse to the
 * overworld, which is precisely the bug this class replaces.
 *
 * <p>Note that a custom dimension's {@code <path>} may itself contain slashes,
 * because {@code Identifier} permits {@code /} in the path component. So the
 * namespace is the first segment after {@code dimensions/} and the path is
 * everything after it, joined with {@code /}.
 */
public final class DimensionKey {

    /** Leaf directory names that {@code RegionBasedStorage} is given. */
    private static final String REGION_LEAF = "region";
    private static final String POI_LEAF = "poi";

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String THE_NETHER = "minecraft:the_nether";
    public static final String THE_END = "minecraft:the_end";

    /**
     * Matches all four save-directory layouts in one pass, anchored at the end.
     *
     * <pre>
     *   ^(?&lt;root&gt;(?:(?![/\\]dimensions(?:[/\\]|$)).)*?)     root, may not contain a 'dimensions' segment
     *   (?:[/\\](?:                                          optional dimension part:
     *       (?&lt;vanilla&gt;DIM-1|DIM1)                             nether or end, or
     *     | dimensions[/\\](?&lt;namespace&gt;[a-z0-9_.-]+)          custom: namespace
     *                  [/\\](?&lt;path&gt;[a-z0-9_./-]+)             custom: path, may contain '/'
     *   ))?
     *   [/\\](?&lt;leaf&gt;region|poi)[/\\]*$                      the store directory
     * </pre>
     *
     * <p>Three properties fall out of the pattern rather than needing separate
     * checks:
     *
     * <ul>
     *   <li>{@code [/\\]} accepts either separator, so Windows paths parse without
     *       pre-normalising the string.</li>
     *   <li>The character classes <em>are</em> vanilla's {@code Identifier}
     *       charset (namespace {@code [a-z0-9_.-]}, path additionally {@code /}),
     *       so an invalid identity simply fails to match. Since the classes are
     *       lowercase-only, two dimensions cannot collide by case folding on a
     *       case-insensitive filesystem.</li>
     *   <li>The negative lookahead in {@code root} is what makes a malformed
     *       custom path fail instead of falling through to the overworld. Without
     *       it, {@code world/dimensions/region} would parse as an overworld whose
     *       root happened to end in {@code dimensions} -- silently putting two
     *       dimensions in one keyspace, which is the bug this class replaces.</li>
     * </ul>
     *
     * <p>{@code root} is lazy so the optional dimension part is preferred over
     * absorbing it into the root; that is what keeps {@code world/DIM-1/region}
     * from being read as an overworld.
     *
     * <p><b>Known ambiguity.</b> The lookahead also rejects a legitimate world
     * whose <em>ancestor</em> path contains a {@code dimensions} segment, e.g.
     * {@code /mnt/dimensions/world/region}. That path is structurally identical to
     * a malformed custom dimension ({@code dimensions} + one segment + leaf), and
     * distinguishing them would require knowing the world root, which is not
     * available here. Rejecting costs a startup error with an obvious workaround;
     * accepting would risk mapping two dimensions onto one keyspace. Only an exact
     * {@code dimensions} segment is affected -- {@code my-dimensions} and
     * {@code dimensions-backup} parse normally.
     */
    private static final Pattern LAYOUT = Pattern.compile(
        "^(?<root>(?:(?![/\\\\]dimensions(?:[/\\\\]|$)).)*?)"
            + "(?:[/\\\\](?:(?<vanilla>DIM-1|DIM1)"
            + "|dimensions[/\\\\](?<namespace>[a-z0-9_.-]+)[/\\\\](?<path>[a-z0-9_./-]+)))?"
            + "[/\\\\](?<leaf>" + REGION_LEAF + "|" + POI_LEAF + ")[/\\\\]*$");

    private final String identity;
    private final String leaf;

    private DimensionKey(String identity, String leaf) {
        this.identity = identity;
        this.leaf = leaf;
    }

    /**
     * The dimension's namespaced identity, e.g. {@code minecraft:overworld} or
     * {@code twilightforest:twilight_forest}.
     */
    public String identity() {
        return this.identity;
    }

    /** Which store this directory belongs to: {@code region} or {@code poi}. */
    public String leaf() {
        return this.leaf;
    }

    /**
     * Parses a storage directory into a dimension identity.
     *
     * @param directory the directory handed to {@code RegionBasedStorage}, whose
     *                  final segment is {@code region} or {@code poi}
     * @throws IllegalArgumentException if the layout is not recognised. Failing
     *                                  here is deliberate: guessing would risk
     *                                  writing two dimensions to one keyspace.
     */
    public static DimensionKey fromStorageDirectory(File directory) {
        if (directory == null) {
            throw new IllegalArgumentException("storage directory is null");
        }
        String path = directory.getAbsolutePath();

        Matcher m = LAYOUT.matcher(path);
        if (!m.matches()) {
            throw new IllegalArgumentException(
                "unrecognised storage directory layout, expected <root>/region, "
                    + "<root>/DIM-1/region, <root>/DIM1/region or "
                    + "<root>/dimensions/<namespace>/<path>/region (or the poi "
                    + "equivalents): " + path);
        }

        String leaf = m.group("leaf");
        String vanilla = m.group("vanilla");
        String namespace = m.group("namespace");

        if (vanilla != null) {
            return new DimensionKey("DIM-1".equals(vanilla) ? THE_NETHER : THE_END, leaf);
        }
        if (namespace != null) {
            return new DimensionKey(namespace + ":" + m.group("path"), leaf);
        }
        return new DimensionKey(OVERWORLD, leaf);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DimensionKey)) {
            return false;
        }
        DimensionKey other = (DimensionKey)o;
        return this.identity.equals(other.identity) && this.leaf.equals(other.leaf);
    }

    @Override
    public int hashCode() {
        return this.identity.hashCode() * 31 + this.leaf.hashCode();
    }

    @Override
    public String toString() {
        return this.identity + "[" + this.leaf + "]";
    }
}
