package descant.client.api.actions;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * The blocks that mean the same thing as each other.
 *
 * <p>Every ore in the game exists two or three times over. Below y=0 there is no {@code diamond_ore}
 * at all — it is all {@code deepslate_diamond_ore} — so a bot sent to find diamonds by the name a
 * person would use searches an entire cave system and reports nothing, at exactly the depth where
 * every diamond in the world actually is. The same trap sits under iron, copper, gold, redstone,
 * lapis, emerald and coal, and again under gold in the Nether.</p>
 *
 * <p>Derived from the names rather than listed out, so a version that adds an ore needs no change
 * here. Restricted to ids ending in {@code _ore} on purpose: the prefixes are a real pattern among
 * ores and a coincidence elsewhere, and treating {@code bricks} and {@code deepslate_bricks} as one
 * thing would be a wrong answer given confidently.</p>
 */
public final class BlockFamily {

	/** The prefixes an ore's stone-type variants are spelled with. */
	private static final List<String> VARIANTS = List.of("deepslate_", "nether_");

	private BlockFamily() {
	}

	/**
	 * Every block that counts as the same thing as this one, the asked-for one included.
	 *
	 * <p>Never empty, so a caller can search against it unconditionally.</p>
	 */
	public static Set<Block> of(Block block) {
		String path = path(block);
		if (!path.endsWith("_ore")) {
			return Set.of(block);
		}

		// Whichever variant was named, work back to the plain form and then out again — so asking for
		// deepslate_diamond_ore finds the ordinary kind too, not only the other way round.
		String base = path;
		for (String prefix : VARIANTS) {
			if (path.startsWith(prefix)) {
				base = path.substring(prefix.length());
				break;
			}
		}

		Set<Block> family = new LinkedHashSet<>();
		add(family, base);
		for (String prefix : VARIANTS) {
			add(family, prefix + base);
		}
		family.add(block); // the named one always belongs, whatever the naming rules made of it
		return Collections.unmodifiableSet(family);
	}

	/**
	 * The family written out for a person, or the single name when there is only one.
	 *
	 * <p>Said out loud rather than left implicit: a bot that reports "12x diamond_ore" while standing
	 * in deepslate has told a small lie, and the caller's next decision — which pickaxe, which depth —
	 * depends on knowing which it really was.</p>
	 */
	public static String describe(Set<Block> family) {
		return family.stream().map(BlockFamily::path).collect(Collectors.joining(" or "));
	}

	private static void add(Set<Block> family, String path) {
		Block block = BuiltInRegistries.BLOCK
				.getOptional(Identifier.withDefaultNamespace(path))
				.orElse(null);
		if (block != null && block != Blocks.AIR) {
			family.add(block);
		}
	}

	private static String path(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getPath();
	}
}
