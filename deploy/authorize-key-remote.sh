#!/usr/bin/env bash
# Runs ON THE VPS, shipped by the "Authorize deploy key" workflow. Reads one public key
# line on stdin and appends it to authorized_keys of the user it runs as. When that user
# is not root but has passwordless sudo, the key is installed for root as well, so a
# repository whose DEPLOY_USER is root (agentco) can deploy too. Idempotent.
set -euo pipefail

key="$(cat)"
if [ -z "$key" ]; then
  echo "no key on stdin" >&2
  exit 1
fi

add() { # $1 = home directory, $2 = label for the log
  umask 077
  mkdir -p "$1/.ssh"
  touch "$1/.ssh/authorized_keys"
  if grep -qxF "$key" "$1/.ssh/authorized_keys"; then
    echo "$2: already authorized"
  else
    printf '%s\n' "$key" >> "$1/.ssh/authorized_keys"
    echo "$2: authorized"
  fi
}

echo "logged in as $(whoami) on $(hostname)"
add "$HOME" "$(whoami)"

if [ "$(id -u)" != 0 ]; then
  if sudo -n true 2>/dev/null; then
    printf '%s\n' "$key" | sudo -n bash -c "key=\"\$(cat)\"; $(declare -f add); add /root root"
  else
    echo "root: skipped, $(whoami) has no passwordless sudo"
  fi
fi
