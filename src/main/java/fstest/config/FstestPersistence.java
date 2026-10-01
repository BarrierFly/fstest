package fstest.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import fstest.FstestMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.DyeColor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Persists the sticky {@link FstestConfig} to {@code <config>/fstest.json}.
 *
 * Loaded once per server start (after {@link FstestConfig#reset()}); saved back
 * after every successful mutation via the config's change listener. The file is
 * human-editable; unknown keys are ignored and per-field invalid values fall
 * back to the field's default with a warning instead of rejecting the whole
 * file.
 */
public final class FstestPersistence
{
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private FstestPersistence()
	{
	}

	private static Path file()
	{
		return FabricLoader.getInstance().getConfigDir().resolve("fstest.json");
	}

	/** Registers the save-back hook and applies the on-disk state, if any. */
	public static void initialize()
	{
		FstestConfig.INSTANCE.setChangeListener(FstestPersistence::save);
		load();
	}

	private static void load()
	{
		Path file = file();
		if (!Files.exists(file))
		{
			return;
		}
		JsonObject json;
		try
		{
			json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
		}
		catch (Exception e)
		{
			FstestMod.LOGGER.error("[fstest] config file {} is corrupt; keeping defaults", file, e);
			return;
		}

		FstestConfig cfg = FstestConfig.INSTANCE;
		cfg.setLoading(true);
		try
		{
			FstestMode mode = parseEnum(json, "mode", FstestMode.class, null);
			if (mode != null)
			{
				cfg.setMode(mode);
			}
			DyeColor color = parseColor(json);
			if (color != null)
			{
				cfg.setColor(Optional.of(color));
			}
			cfg.setCount("d", getInt(json, "countD", cfg.countD(), 0, Integer.MAX_VALUE));
			cfg.setCount("p", getInt(json, "countP", cfg.countP(), 0, Integer.MAX_VALUE));
			cfg.setCount("pd", getInt(json, "countPd", cfg.countPd(), 0, Integer.MAX_VALUE));
			cfg.setRange(getInt(json, "range", cfg.range(), -1, FstestConfig.MAX_RANGE));
			PStrategy strategy = parseEnum(json, "pstrategy", PStrategy.class, null);
			if (strategy != null && strategy != PStrategy.HASH_DELTA)
			{
				cfg.setStrategy(strategy);
			}
			Boolean updates = getBoolean(json, "updates");
			if (updates != null)
			{
				cfg.setUpdates(updates);
			}
			Boolean duplications = getBoolean(json, "duplications");
			if (duplications != null)
			{
				cfg.setDuplications(duplications);
			}
			cfg.setMtrTicks(getInt(json, "mtrTicks", 0, 0, FstestConfig.MAX_MTR_TICKS));
			FstestConfig.Area area = getArea(json);
			if (area != null)
			{
				cfg.setMtrArea(area);
			}
			loadTargets(cfg, json);
		}
		finally
		{
			cfg.setLoading(false);
		}
	}

	private static void save()
	{
		JsonObject json = new JsonObject();
		FstestConfig cfg = FstestConfig.INSTANCE;
		json.addProperty("mode", cfg.mode().name());
		json.addProperty("color", cfg.color().map(DyeColor::getName).orElse(null));
		json.addProperty("countD", cfg.countD());
		json.addProperty("countP", cfg.countP());
		json.addProperty("countPd", cfg.countPd());
		json.addProperty("range", cfg.range());
		json.addProperty("pstrategy", cfg.strategy().name());
		json.addProperty("updates", cfg.updates());
		json.addProperty("duplications", cfg.duplications());
		json.addProperty("mtrTicks", cfg.mtrTicks());
		FstestConfig.Area area = cfg.mtrArea();
		if (area != null)
		{
			JsonObject a = new JsonObject();
			a.addProperty("x1", area.pos1().getX());
			a.addProperty("y1", area.pos1().getY());
			a.addProperty("z1", area.pos1().getZ());
			a.addProperty("x2", area.pos2().getX());
			a.addProperty("y2", area.pos2().getY());
			a.addProperty("z2", area.pos2().getZ());
			json.add("mtrArea", a);
		}
		JsonArray targets = new JsonArray();
		for (Map.Entry<BlockPos, DyeColor> entry : cfg.targets().entrySet())
		{
			JsonObject t = new JsonObject();
			t.addProperty("color", entry.getValue().getName());
			t.addProperty("x", entry.getKey().getX());
			t.addProperty("y", entry.getKey().getY());
			t.addProperty("z", entry.getKey().getZ());
			targets.add(t);
		}
		json.add("targets", targets);

		try
		{
			Path file = file();
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(json), StandardCharsets.UTF_8);
		}
		catch (IOException e)
		{
			FstestMod.LOGGER.error("[fstest] failed to write config file {}", file(), e);
		}
	}

	private static <E extends Enum<E>> E parseEnum(JsonObject json, String key, Class<E> type, E fallback)
	{
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive())
		{
			return fallback;
		}
		try
		{
			return Enum.valueOf(type, element.getAsString().trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			FstestMod.LOGGER.warn("[fstest] config key '{}' has invalid value '{}'; using default", key, element.getAsString());
			return fallback;
		}
	}

	private static DyeColor parseColor(JsonObject json)
	{
		JsonElement element = json.get("color");
		if (element == null || element.isJsonNull() || !element.isJsonPrimitive())
		{
			return null; // absent/null = "all colours"
		}
		String name = element.getAsString().trim().toLowerCase(Locale.ROOT);
		for (DyeColor color : DyeColor.values())
		{
			if (color.getName().equals(name))
			{
				return color;
			}
		}
		FstestMod.LOGGER.warn("[fstest] config key 'color' has invalid value '{}'; using 'all'", name);
		return null;
	}

	private static int getInt(JsonObject json, String key, int fallback, int min, int max)
	{
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive())
		{
			return fallback;
		}
		try
		{
			int value = element.getAsInt();
			if (value < min || value > max)
			{
				throw new NumberFormatException("out of range");
			}
			return value;
		}
		catch (NumberFormatException e)
		{
			FstestMod.LOGGER.warn("[fstest] config key '{}' has invalid value '{}'; using default", key, element);
			return fallback;
		}
	}

	private static Boolean getBoolean(JsonObject json, String key)
	{
		JsonElement element = json.get(key);
		if (element == null || !element.isJsonPrimitive())
		{
			return null;
		}
		try
		{
			return element.getAsBoolean();
		}
		catch (Exception e)
		{
			FstestMod.LOGGER.warn("[fstest] config key '{}' is not a boolean; using default", key);
			return null;
		}
	}

	private static FstestConfig.Area getArea(JsonObject json)
	{
		JsonElement element = json.get("mtrArea");
		if (element == null || element.isJsonNull())
		{
			return null;
		}
		if (!element.isJsonObject())
		{
			FstestMod.LOGGER.warn("[fstest] config key 'mtrArea' is malformed; ignoring");
			return null;
		}
		JsonObject a = element.getAsJsonObject();
		try
		{
			return FstestConfig.Area.of(
					new BlockPos(a.get("x1").getAsInt(), a.get("y1").getAsInt(), a.get("z1").getAsInt()),
					new BlockPos(a.get("x2").getAsInt(), a.get("y2").getAsInt(), a.get("z2").getAsInt()));
		}
		catch (Exception e)
		{
			FstestMod.LOGGER.warn("[fstest] config key 'mtrArea' is malformed; ignoring", e);
			return null;
		}
	}

	private static void loadTargets(FstestConfig cfg, JsonObject json)
	{
		JsonElement element = json.get("targets");
		if (element == null || !element.isJsonArray())
		{
			return;
		}
		for (JsonElement item : element.getAsJsonArray())
		{
			try
			{
				JsonObject t = item.getAsJsonObject();
				String colorName = t.get("color").getAsString().trim().toLowerCase(Locale.ROOT);
				DyeColor color = null;
				for (DyeColor candidate : DyeColor.values())
				{
					if (candidate.getName().equals(colorName))
					{
						color = candidate;
						break;
					}
				}
				if (color == null)
				{
					throw new IllegalArgumentException("unknown colour " + colorName);
				}
				cfg.putTarget(
						new BlockPos(t.get("x").getAsInt(), t.get("y").getAsInt(), t.get("z").getAsInt()),
						color);
			}
			catch (Exception e)
			{
				FstestMod.LOGGER.warn("[fstest] config 'targets' entry is malformed; skipping", e);
			}
		}
	}
}
