<!--
  Public update notes — announced to Discord automatically.

  On boot the game server compares this file's content against the last
  version it announced (stored in Mongo `server_meta`). If it changed, it
  posts ONE update notification to the Discord news channel. If it hasn't
  changed, the boot is silent — plain restarts no longer ping Discord.

  Format: the first line is the announcement title (a leading '#' is
  stripped), everything after it is the body (Discord markdown). HTML
  comments like this one are stripped before posting.

  Keep it HIGH LEVEL — this is community-facing. No exploit details, no
  unreleased content, no secrets. Edit this file as part of the change you
  are shipping, then push.
-->
# Game Update

- The Last Free City: the goblins at the east camp are now the ordinary level-2 Lumbridge goblins. They no longer attack on sight or pile onto new players — you pick your fights, and you have time to eat.
- The Last Free City: goblin kills at the east camp now count toward the "defeat 5 goblins" objective as long as you hit the goblin — the counter was stuck at 0/5.
- Quest Journal: the east-camp goblins are highlighted again during The Last Free City (the arrow hands off to them once they are in view). The client updates itself on next launch.
- Deploy announcements: Discord now gets a proper update post when we ship changes, instead of a "server is online" message on every restart.
- General behind-the-scenes improvements and maintenance.
