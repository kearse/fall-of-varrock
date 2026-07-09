package net.runelite.cache;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import net.runelite.cache.definitions.InterfaceDefinition;
import net.runelite.cache.definitions.SpriteDefinition;
import net.runelite.cache.fs.Store;

/**
 * One-off: decode the wilderness overlay interface (group 90), print its components, and export
 * every referenced sprite to PNG so we can pick out the skull and bundle it into the client.
 * Paths hardcoded (the cache path has spaces, which exec.args would split).
 */
public class SkullExtract
{
	private static final String CACHE = "C:\\Program Files (x86)\\Kearse RSPS\\Alter\\data\\cache";
	private static final String OUT = "C:\\Users\\zakea\\rsps-client\\skull-extract";
	private static final int GROUP = 90;

	public static void main(String[] args) throws Exception
	{
		File out = new File(OUT);
		out.mkdirs();

		Store store = new Store(new File(CACHE));
		store.load();

		InterfaceManager im = new InterfaceManager(store);
		im.load();
		SpriteManager sm = new SpriteManager(store);
		sm.load();

		InterfaceDefinition[] g = im.getIntefaceGroup(GROUP);
		System.out.println("=== interface group " + GROUP + " : " + (g == null ? 0 : g.length) + " children ===");
		if (g != null)
		{
			for (InterfaceDefinition d : g)
			{
				if (d == null)
				{
					continue;
				}
				int child = d.id & 0xffff;
				System.out.println("child=" + child + " type=" + d.type + " contentType=" + d.contentType
					+ " spriteId=" + d.spriteId + " modelId=" + d.modelId
					+ " w=" + d.originalWidth + " h=" + d.originalHeight);
				if (d.spriteId >= 0)
				{
					export(sm, d.spriteId, out, "g" + GROUP + "_c" + child + "_s" + d.spriteId);
				}
			}
		}
	}

	private static void export(SpriteManager sm, int spriteId, File out, String name)
	{
		try
		{
			SpriteDefinition sd = sm.findSprite(spriteId, 0);
			if (sd == null || sd.getWidth() <= 0 || sd.getHeight() <= 0)
			{
				System.out.println("  sprite " + spriteId + " missing/empty");
				return;
			}
			BufferedImage img = sm.getSpriteImage(sd);
			File f = new File(out, name + ".png");
			ImageIO.write(img, "png", f);
			System.out.println("  wrote " + f.getName() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
		}
		catch (Exception e)
		{
			System.out.println("  export " + spriteId + " failed: " + e);
		}
	}
}
