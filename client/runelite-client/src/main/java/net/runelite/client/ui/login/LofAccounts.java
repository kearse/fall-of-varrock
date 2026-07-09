/*
 * Fall of Varrock — saved-accounts store for the custom login screen.
 *
 * Persists "remember me" accounts to ~/.fov-home/fov-accounts.dat. Passwords are
 * lightly obfuscated (XOR + Base64), NOT encrypted — local remember-me is never a
 * real secret store, but we avoid writing them in plaintext. Newest first, capped.
 */
package net.runelite.client.ui.login;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Singleton
public class LofAccounts
{
	static final class Account
	{
		final String username;
		final String password;

		Account(String username, String password)
		{
			this.username = username;
			this.password = password;
		}
	}

	private static final int MAX = 6;
	private static final byte[] KEY = "FallOfVarrock-remember".getBytes(StandardCharsets.UTF_8);

	private final Path file = Paths.get(System.getProperty("user.home"), ".fov-home", "fov-accounts.dat");

	public List<Account> load()
	{
		List<Account> out = new ArrayList<>();
		try
		{
			if (!Files.exists(file))
			{
				return out;
			}
			for (String line : Files.readAllLines(file, StandardCharsets.UTF_8))
			{
				int tab = line.indexOf('\t');
				if (tab <= 0)
				{
					continue;
				}
				String user = line.substring(0, tab);
				String pass = deobfuscate(line.substring(tab + 1));
				out.add(new Account(user, pass));
			}
		}
		catch (Exception e)
		{
			log.warn("could not read saved accounts", e);
		}
		return out;
	}

	/** Add/refresh an account at the front, cap the list, persist. */
	public void remember(String username, String password)
	{
		if (username == null || username.isEmpty())
		{
			return;
		}
		List<Account> accts = load();
		accts.removeIf(a -> a.username.equalsIgnoreCase(username));
		accts.add(0, new Account(username, password));
		while (accts.size() > MAX)
		{
			accts.remove(accts.size() - 1);
		}
		save(accts);
	}

	public void forget(String username)
	{
		List<Account> accts = load();
		if (accts.removeIf(a -> a.username.equalsIgnoreCase(username)))
		{
			save(accts);
		}
	}

	private void save(List<Account> accts)
	{
		try
		{
			Files.createDirectories(file.getParent());
			StringBuilder sb = new StringBuilder();
			for (Account a : accts)
			{
				sb.append(a.username).append('\t').append(obfuscate(a.password)).append('\n');
			}
			Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			log.warn("could not save accounts", e);
		}
	}

	private static String obfuscate(String s)
	{
		byte[] b = s.getBytes(StandardCharsets.UTF_8);
		for (int i = 0; i < b.length; i++)
		{
			b[i] ^= KEY[i % KEY.length];
		}
		return Base64.getEncoder().encodeToString(b);
	}

	private static String deobfuscate(String s)
	{
		byte[] b = Base64.getDecoder().decode(s);
		for (int i = 0; i < b.length; i++)
		{
			b[i] ^= KEY[i % KEY.length];
		}
		return new String(b, StandardCharsets.UTF_8);
	}
}
