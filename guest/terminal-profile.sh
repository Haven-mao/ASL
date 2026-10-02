# ASL owns terminal tabs; keep normal shell shortcuts available.
if [ -z "${TMUX:-}" ] && [ -t 0 ] && [ "$(tty)" = /dev/ttyAMA0 ] && command -v tmux >/dev/null 2>&1; then
    exec tmux -L asl new-session -A -s main
fi
