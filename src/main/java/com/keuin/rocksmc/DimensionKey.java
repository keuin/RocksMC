package com.keuin.rocksmc;

import java.io.File;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A dimension's stable identity and world root, derived from its save directory.
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
 *
 * <p>In every layout {@code <root>} is the world directory, which is why
 * {@link #root()} can be read off the same match: it is the anchor that groups all
 * of a world's storage directories onto one database.
 */
public final class DimensionKey {

    /**
     * Leaf directory names that {@code RegionBasedStorage} is given.
     */
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
    private final String root;

    private DimensionKey(String identity, String leaf, String root) {
        this.identity = identity;
        this.leaf = leaf;
        this.root = normalise(root);
    }

    /**
     * The dimension's namespaced identity, e.g. {@code minecraft:overworld} or
     * {@code twilightforest:twilight_forest}.
     */
    public String identity() {
        return this.identity;
    }

    /**
     * Which store this directory belongs to: {@code region} or {@code poi}.
     */
    public String leaf() {
        return this.leaf;
    }

    /**
     * The world root: the directory holding {@code level.dat}, above any
     * {@code DIM-1} or {@code dimensions/...} segment.
     *
     * <p>This is what makes one database per world addressable. All six storage
     * directories of a three-dimension world -- {@code <root>/region},
     * {@code <root>/poi}, {@code <root>/DIM-1/region} and so on -- yield the same
     * root, so they resolve to the same database.
     *
     * <p>Recovered from the same match that yields the identity rather than by
     * counting {@code getParentFile()} calls, because the number of segments to
     * climb differs per layout (one for the overworld, two for the nether, three or
     * more for a custom dimension whose path may itself contain slashes). Deriving
     * both from one regex means they cannot disagree.
     *
     * <p>Not canonicalised: this class is a pure function of its input string and
     * must stay testable against paths that do not exist. Redundant {@code .} and
     * {@code ..} segments <em>are</em> removed, on construction, because
     * {@code getAbsolutePath()} leaves them in place -- a server launched with
     * {@code ./world} would otherwise produce {@code /srv/./world/rocksmc.db} in log
     * lines and error messages, which looks like a different path to an operator
     * comparing them. Symlinks are resolved by {@link RocksDatabase#open}, which
     * needs a true identity rather than a tidy one.
     */
    public File root() {
        return new File(this.root);
    }

    /**
     * Removes redundant {@code .} and {@code ..} segments without touching the
     * filesystem.
     *
     * <p>Applied in the constructor rather than in {@link #root()} so that the
     * normalised form is the only form this object holds. Normalising on the way out
     * instead left {@link #equals} comparing the verbatim string while callers keyed
     * their maps on the normalised one, so two spellings of a single world compared
     * unequal while resolving to the same database.
     *
     * <p>{@code Paths.get(...).normalize()} is purely lexical, so it works on paths
     * that do not exist -- which this class must support, since it is a pure function
     * of its input and is tested against paths that were never created.
     *
     * <p>This cannot strip absoluteness in practice: {@link #fromStorageDirectory}
     * resolves through {@code getAbsolutePath()} before matching, so the root it
     * passes here is already absolute. {@link #withRoot} may be given a relative
     * path, but that is harness-only and {@link RocksDatabase#open} canonicalises
     * whatever it receives.
     */
    private static String normalise(String path) {
        if (path.isEmpty()) {
            return path;
        }
        try {
            String normalised = Paths.get(path).normalize().toString();
            // Defensive: no input reaching here is known to normalise to empty, but
            // returning "" would silently retarget the database at the working
            // directory, so the original is preferred over that.
            return normalised.isEmpty() ? path : normalised;
        } catch (RuntimeException e) {
            // An unparseable path (e.g. a NUL byte) is not worth failing over here;
            // the caller will fail on it soon enough with a better message.
            return path;
        }
    }

    /**
     * A copy addressing the same dimension and leaf under a different world root.
     *
     * <p>For harnesses that read a world in place but must not write into it: the
     * source may be a read-only mirror, and leaving a database behind in it would
     * be both surprising and, for the fidelity harness, a source of stale data on a
     * later run. The identity and leaf are preserved, so the dimension ordinal and
     * column family are exactly what the server would use.
     */
    public DimensionKey withRoot(File newRoot) {
        return new DimensionKey(this.identity, this.leaf, newRoot.getPath());
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
        // An absolute path always begins with a separator, so the shortest input
        // that can match ("/region") leaves root empty. Mapping that to the
        // filesystem root keeps root() absolute; letting File("") through would
        // silently resolve against the working directory instead.
        String root = m.group("root");
        if (root.isEmpty()) {
            root = File.separator;
        }

        if (vanilla != null) {
            return new DimensionKey(
                "DIM-1".equals(vanilla) ? THE_NETHER : THE_END, leaf, root);
        }
        if (namespace != null) {
            return new DimensionKey(namespace + ":" + m.group("path"), leaf, root);
        }
        return new DimensionKey(OVERWORLD, leaf, root);
    }

    /**
     * Equality covers the root as well as the identity and leaf.
     *
     * <p>Two keys with the same identity and leaf under different roots address
     * <em>different databases</em>. Ignoring the root would make them compare
     * equal, so a caller using this as a map key would silently conflate two
     * separate worlds -- the same shape of bug as the dimension-identity collision
     * this class was written to fix.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DimensionKey)) {
            return false;
        }
        DimensionKey other = (DimensionKey) o;
        return this.identity.equals(other.identity)
            && this.leaf.equals(other.leaf)
            && this.root.equals(other.root);
    }

    @Override
    public int hashCode() {
        int h = this.identity.hashCode();
        h = h * 31 + this.leaf.hashCode();
        return h * 31 + this.root.hashCode();
    }

    @Override
    public String toString() {
        return this.identity + "[" + this.leaf + "]";
    }
}
