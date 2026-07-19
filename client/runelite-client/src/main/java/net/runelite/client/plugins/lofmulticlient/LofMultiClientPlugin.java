/*
 * Fall of Varrock — multi-client button.
 *
 * Players run several accounts at once, so the title bar gets an "Open another
 * client" button that spawns a completely separate client process. Relaunching
 * ourselves (instead of e.g. opening a new window in-process) keeps every client
 * isolated: its own JVM, its own game connection, its own crash domain.
 *
 * How the relaunch command is derived:
 *  - Installed client: the process command is the jpackage native launcher
 *    ("Fall of Varrock.exe" / the .app or app-image binary). Executing it again
 *    is a full fresh launch — FovLauncher update check included. This is also
 *    the only way to get a second instance on macOS, where clicking the Dock or
 *    Finder icon again just focuses the running app.
 *  - Dev / plain-jar runs: the command is a java binary, so rebuild the line
 *    from sun.java.command (original main class or jar + program args) plus the
 *    current JVM options, minus debug-agent flags whose ports a second JVM
 *    could not bind.
 *
 * Nothing else limits concurrency: the config dir (~/.fov-home) is shared but
 * RuneLite merges concurrent profile writes, and the server allows many
 * connections per address (see NetworkServiceFactory.getINetAddressHandlers).
 */
package net.runelite.client.plugins.lofmulticlient;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.inject.Inject;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@Slf4j
@PluginDescriptor(
	name = "Fall of Varrock Multi-Client",
	description = "Adds a title-bar button that opens another client for playing multiple accounts.",
	tags = {"fov", "lof", "multi", "client", "alt"},
	enabledByDefault = true,
	hidden = true // not player-toggleable: it's part of the client chrome
)
public class LofMultiClientPlugin extends Plugin
{
	@Inject
	private ClientToolbar clientToolbar;

	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		navButton = NavigationButton.builder()
			.tooltip("Open another client")
			.icon(createIcon())
			.priority(12)
			.onClick(this::launchNewClient)
			.build();
		clientToolbar.addNavigation(navButton);
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
	}

	private void launchNewClient()
	{
		try
		{
			List<String> cmd = buildRelaunchCommand();
			if (cmd == null)
			{
				throw new IllegalStateException("could not determine how this client was launched");
			}
			log.info("opening another client: {}", cmd);
			new ProcessBuilder(cmd)
				.redirectOutput(ProcessBuilder.Redirect.DISCARD)
				.redirectError(ProcessBuilder.Redirect.DISCARD)
				.start();
		}
		catch (Exception e)
		{
			log.warn("failed to open another client", e);
			SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(null,
				"Couldn't open another client. Launch a second one from your desktop shortcut instead.",
				"Fall of Varrock", JOptionPane.WARNING_MESSAGE));
		}
	}

	/** The command line that starts a fresh copy of this client, or null if unknowable. */
	private static List<String> buildRelaunchCommand()
	{
		String command = ProcessHandle.current().info().command().orElse(null);
		if (command == null)
		{
			return null;
		}

		String exe = Paths.get(command).getFileName().toString().toLowerCase(Locale.ROOT);
		boolean javaBinary = exe.equals("java") || exe.equals("java.exe")
			|| exe.equals("javaw") || exe.equals("javaw.exe");
		if (!javaBinary)
		{
			// jpackage native launcher — rerunning it is a complete fresh launch
			return List.of(command);
		}

		String sunCommand = System.getProperty("sun.java.command", "");
		if (sunCommand.isEmpty())
		{
			return null;
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(command);
		for (String vmArg : ManagementFactory.getRuntimeMXBean().getInputArguments())
		{
			// a second JVM can't bind the same debugger/agent port
			if (vmArg.startsWith("-agentlib:jdwp") || vmArg.startsWith("-javaagent"))
			{
				continue;
			}
			cmd.add(vmArg);
		}

		// sun.java.command = "<mainClass|main.jar> <program args...>"
		String[] parts = sunCommand.split(" ");
		if (parts[0].endsWith(".jar"))
		{
			cmd.add("-jar");
			cmd.add(parts[0]);
		}
		else
		{
			cmd.add("-cp");
			cmd.add(System.getProperty("java.class.path"));
			cmd.add(parts[0]);
		}
		cmd.addAll(Arrays.asList(parts).subList(1, parts.length));
		return cmd;
	}

	/** Two overlapping window frames with a plus — drawn in code so no binary asset ships. */
	private static BufferedImage createIcon()
	{
		BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		Color gold = new Color(0xE8, 0xC9, 0x7A);
		g.setStroke(new BasicStroke(1.3f));
		g.setColor(new Color(0xB9, 0xA8, 0x86));
		g.drawRect(1, 1, 9, 8);
		g.setColor(new Color(0x2b, 0x22, 0x1a));
		g.fillRect(5, 5, 10, 9);
		g.setColor(gold);
		g.drawRect(5, 5, 9, 8);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
		g.drawString("+", 7, 13);
		g.dispose();
		return img;
	}
}
