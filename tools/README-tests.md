# Automated Windows tests

`tools/run-tests.ps1` runs the desktop app's built-in test scenarios (`AutoTest.kt`, development only: off unless
`NOVAPLAY_AUTOTEST` is set, never in a release) one at a time, safely and without anyone at the keyboard.

## Run

From the repository root, in PowerShell:

```powershell
# list the scenarios and their time limits
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1 -List

# one scenario
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1 fullscreen

# a safe short batch (about 15 min)
powershell -NoProfile -ExecutionPolicy Bypass -File tools\run-tests.ps1 chancheck fullscreen netmulti
```

Options:

| Option | Default | Meaning |
|---|---|---|
| `-Screens 2..4` | `2` | multi-view screens the scenarios use |
| `-OutDir <path>` | `%LOCALAPPDATA%\NovaPlayTests` (or `NOVAPLAY_TEST_DIR`) | runs, data copy and lock |
| `-JavaHome <path>` | `JAVA_HOME`, else newest `~\.jdks\jdk-17*` | JDK for Gradle |
| `-AllowLong` | off | needed for long scenarios (`netsoak`, about an hour) |

The script exits 0 only if every scenario passed.

## Scenarios

| Scenario | Takes | Checks |
|---|---|---|
| `fullscreen` | ~3 min | 20 channel changes and 10 player opens, each judged; focus; players freed |
| `chancheck` | ~4 min | 12 channels, each judged after it settles and again 8 s later |
| `netmulti` | ~5 min | multi-view, 6 network drops through a local relay; recovery, no silent frozen screen |
| `launch` | ~1 min | focus and keys at start-up |
| `netdrop`, `netfreeze` | ~3-10 min | network drop / frozen-picture recovery in multi-view |
| `multiview`, `cycles`, `limits`, `probes`, `spike` | ~2-10 min | multi-view features, repeated cycles, limits |
| `netsoak` | ~60 min | long run (needs `-AllowLong`); only with explicit approval |

A picture is judged from VLC's state, its clock, frames delivered since the channel opened, the video track,
brightness and when the picture last changed. Fine: moving, still, black (dead feeds send black video), spinner,
error message, starting (first 12 s). Failures, only after start-up and with nothing on screen saying why: stopped,
no video, no frames, frozen.

## Results

Each run gets a unique id and folder `<OutDir>\runs\<batch>-<scenario>-<n>\`:

- `app.log`: the app's own log, starting `BEGIN run=<id>` and ending `END run=<id> status=...`
- `monitor.log`: CPU, free memory, app memory, responsiveness every 3 s
- `gradle.log`: build and launch output
- `status.txt`: the verdict

`<OutDir>\runs\summary-<batch>.txt` lists every scenario's verdict and checks.

| Status | Meaning |
|---|---|
| `PASS` | the app logged END for this run id with no failed check, and exited 0 |
| `FAIL` | a check failed (see the `FAIL` / `FAILING` lines) |
| `TIMEOUT` | ran past its time limit (in the app, or 2 min later by the runner) |
| `INCOMPLETE` | never started, or no END line (crashed or killed); never counted as a pass |
| `STOPPED_*` | a resource stop condition (CPU, memory, not responding, hung, players, heap) |
| `SKIPPED_RESOURCES` | the PC was too busy to start |

After anything other than PASS or FAIL the rest of the batch is not started.

## Safeguards

- Start only with >= 3 GB free memory and system CPU <= 60%.
- Stop if free memory < 1.5 GB, CPU > 90% for 18 s, the app above 2.5 GB, the window not responding for 30 s, the log
  silent for 2 min, more than 6 VLC players or 1.5 GB Java heap, or the time limit.
- Cleanup stops only what the runner started: the test app (by the pid it logs) and Gradle (`--no-daemon`) with its
  children. The installed NovaPlay and every other program are left alone.
- One runner at a time (`runner.lock`).
- Test data: NovaPlay's playlists, settings and guide are copied to `<OutDir>\appdata` each batch and the test app
  uses that copy (`APPDATA`), so the installed app's data is never written. A test copy also skips the
  single-instance check, so it runs beside an open NovaPlay without signalling it.
- No keyboard or mouse input goes through Windows: keys are delivered inside the test app's own window.
