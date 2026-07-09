/*
 * Copyright (c) 2018, Kamiel
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.chat;

import java.util.Arrays;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ScriptID;
import net.runelite.api.VarClientStr;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ChatboxInput;
import net.runelite.client.events.PrivateMessageInput;

@Slf4j
@Singleton
class ChatInputManager
{
	private static final String RUNELITE_COMMAND = "runeliteCommand";
	private static final String CHATBOX_INPUT = "chatboxInput";
	private static final String PRIVATE_MESSAGE = "privateMessage";

	private final Client client;
	private final EventBus eventBus;
	private final ClientThread clientThread;
	private boolean sending;

	@Inject
	private ChatInputManager(Client client, EventBus eventBus, ClientThread clientThread)
	{
		this.client = client;
		this.eventBus = eventBus;
		this.clientThread = clientThread;
		eventBus.register(this);
	}

	@Subscribe
	private void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (sending)
		{
			return;
		}

		switch (event.getEventName())
		{
			case RUNELITE_COMMAND:
				runCommand();
				break;
			case CHATBOX_INPUT:
				handleInput(event);
				break;
			case PRIVATE_MESSAGE:
				handlePrivateMessage(event);
				break;
		}
	}

	private void runCommand()
	{
		// Kingdom of Lumbridge: rewrite scam-blocked commands (e.g. ::bank) on the typed-text varc
		// BEFORE the gamepack's scam-check reads it, so it forwards a normal command.
		final String rewritten = rewriteBlockedCommand(client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT));
		if (rewritten != null)
		{
			client.setVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT, rewritten);
		}

		String typedText = client.getVarcStrValue(VarClientStr.CHATBOX_TYPED_TEXT).substring(2); // strip ::

		log.debug("Command: {}", typedText);

		String[] split = typedText.split(" ");

		// Fixes ArrayIndexOutOfBounds when typing ":: "
		if (split.length == 0)
		{
			return;
		}

		String command = split[0];
		String[] args = Arrays.copyOfRange(split, 1, split.length);

		CommandExecuted commandExecuted = new CommandExecuted(command, args);
		eventBus.post(commandExecuted);
	}

	/**
	 * Maps OSRS "scam-bait" :: commands that the vanilla client refuses to forward
	 * (turning them into the "Hey, everyone, I just tried to do something very silly!"
	 * public message) onto a working server alias. Returns the rewritten text, or
	 * null if the input isn't one of them.
	 */
	private static String rewriteBlockedCommand(String typed)
	{
		if (typed == null || !typed.startsWith("::"))
		{
			return null;
		}

		final String body = typed.substring(2);
		final int sp = body.indexOf(' ');
		final String name = (sp < 0 ? body : body.substring(0, sp)).toLowerCase();
		final String rest = sp < 0 ? "" : body.substring(sp);

		// bank -> pbank (the server's "open bank anywhere" alias)
		if (name.equals("bank"))
		{
			return "::pbank" + rest;
		}

		return null;
	}

	private void handleInput(ScriptCallbackEvent event)
	{
		final String[] stringStack = client.getStringStack();
		final int[] intStack = client.getIntStack();
		int stringStackCount = client.getStringStackSize();
		int intStackCount = client.getIntStackSize();

		// Kingdom of Lumbridge: the vanilla OSRS client converts certain "scam-bait" :: commands
		// (e.g. ::bank) into a public-chat troll instead of forwarding them. Rewrite those
		// on the string stack to a non-blocked alias so the gamepack forwards them normally.
		final String rewritten = rewriteBlockedCommand(stringStack[stringStackCount - 1]);
		if (rewritten != null)
		{
			stringStack[stringStackCount - 1] = rewritten;
		}

		final String typedText = stringStack[stringStackCount - 1];
		final int chatType = intStack[intStackCount - 2];
		final int clanTarget = intStack[intStackCount - 1];

		ChatboxInput chatboxInput = new ChatboxInput(typedText, chatType, () -> clientThread.invokeLater(() -> sendChatboxInput(typedText, chatType, clanTarget)));
		eventBus.post(chatboxInput);

		if (chatboxInput.isConsumed())
		{
			// input was blocked.
			stringStack[stringStackCount - 1] = ""; // prevent script from sending
		}
	}

	private void handlePrivateMessage(ScriptCallbackEvent event)
	{
		final String[] stringStack = client.getStringStack();
		final int[] intStack = client.getIntStack();
		int stringStackCount = client.getStringStackSize();
		int intStackCount = client.getIntStackSize();

		final String target = stringStack[stringStackCount - 2];
		final String message = stringStack[stringStackCount - 1];

		PrivateMessageInput privateMessageInput = new PrivateMessageInput(target, message, () -> clientThread.invokeLater(() -> sendPrivmsg(target, message)));
		eventBus.post(privateMessageInput);

		if (privateMessageInput.isConsumed())
		{
			intStack[intStackCount - 1] = 1;
			client.setStringStackSize(stringStackCount - 2); // remove both target and message
		}
	}

	private void sendChatboxInput(String input, int chatType, int clanTarget)
	{
		sending = true;
		try
		{
			client.runScript(ScriptID.CHAT_SEND, input, chatType, clanTarget, 0, -1);
		}
		finally
		{
			sending = false;
		}
	}

	private void sendPrivmsg(String target, String message)
	{
		client.runScript(ScriptID.PRIVMSG, target, message);
	}
}
