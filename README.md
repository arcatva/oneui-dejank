# One UI Dejank

Three memory fixes for animation stutter on Samsung One UI (Snapdragon).
A root module with an app; each fix has its own switch.

| Fix | What changes | Measured¹ |
|---|---|---|
| GPU memory without waiting | The Adreno driver takes a smaller block instead of stalling for a large one | Waits for a large block: 1400–2700 → about 30 per 7 s |
| Larger free-memory reserve | `vm.watermark_scale_factor` → 300 | Drawing threads waiting for memory: 30 ms/s → 0 |
| System UI and Home stay in RAM | Both live in a memory group that is never reclaimed or swapped | Their read-back waits: 240 ms → 25 ms per 7 s |

¹ One Galaxy Z Fold8 (SM-F971B, kernel 6.12 android16), Perfetto traces while switching between heavy apps.

## Requirements

- Root with a module manager: KernelSU, Magisk or APatch.
- The GPU fix needs a Snapdragon phone on kernel `android16-6.12`.
- Tested on the phone above only. A fix that cannot work on a device shows as unavailable and does nothing.

## Install

1. Flash `oneui-dejank-vX.Y.Z.zip` in your root manager. It installs the app.
2. Open **Dejank**, grant root, tap **Apply**. No reboot needed.

After a reboot the module applies whatever is switched on.

## Undo

Switch a fix off, or disable the module and reboot. Nothing is written to the system partitions.

If the phone goes down while the fixes are being applied, the next boot applies nothing until you tap **Resume**.

## How the fixes work

**GPU memory without waiting.** The Adreno driver (`msm_kgsl`) asks for memory in large blocks first. On some phones the device tree sets `qcom,mempool-retry-min-order`, which makes the driver free memory on the spot and retry before it settles for a smaller block. The thread drawing the frame waits for that, often tens of milliseconds. [`kgsl_nowait`](kmod/kgsl_nowait.c) writes 0 to that one number, the driver's own "don't wait", and writes the old value back when unloaded. No code is patched.

**Larger free-memory reserve.** With the stock setting the background reclaimer starts when less than 1% of RAM is free. An app start can use that up faster than it is refilled, and then the app itself has to free memory. At 300 reclaiming starts at about 3%.

**System UI and Home stay in RAM.** Under pressure the kernel compresses or drops parts of System UI and the launcher like any other app, and the notification panel or the home screen waits while they are read back, mostly from flash. The module puts both in a cgroup directly under the root with `memory.min=max` and `memory.swap.max=0`. They restart once so that all their memory starts inside it.

## Build

CI builds everything: [`build.yml`](.github/workflows/build.yml).

```sh
bash tests/run.sh dash          # module scripts against a simulated phone
./gradlew :app:assembleRelease  # the app
```

The kernel module builds against the `android16-6.12` GKI headers; see the `kmod` job.

## License

[GPL-2.0](LICENSE)
