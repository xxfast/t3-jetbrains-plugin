# :cli

A command-line client on `:protocol` and nothing else. It's here to prove `:protocol` needs no
IDE, not to be a product.

```sh
./gradlew :cli:run --args="pair <code-or-link> [--server http://127.0.0.1:3773]"
./gradlew :cli:run --args="threads"
./gradlew :cli:run --args="tail <threadId>"
```

- `pair` exchanges a pairing code or link from the desktop app's Settings > Connections (it
  shows up there as "CLI") and saves `{server, accessToken, expiresAt}` to `~/.t3code-cli.json`,
  owner-only where the OS has POSIX permissions. It prints the scopes the server granted.
- `threads` prints every thread that isn't archived, one per line, tab-separated: id, status,
  project, title. Newest `updatedAt` first, not the Sidebar's order. `T3Client` has no signal for
  "the shell snapshot is in", so an empty server shows "No threads" after 10 seconds.
- `tail` prints the thread's last 10 items, then each new or changed one, until Ctrl+C. One
  line per item: type, status, and its text, title or command. A message that's still streaming
  prints once it's done.

Errors print one line and exit 1, bad arguments exit 2. `./gradlew :cli:installDist` puts a
`t3` script in `cli/build/install/t3/bin`.
