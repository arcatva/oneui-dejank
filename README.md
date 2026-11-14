# One UI Dejank

Three memory fixes for animation stutter on Samsung One UI (Snapdragon).
A root module with an app; each fix has its own switch.

| Fix | What changes | Measured¹ |
|---|---|---|
| No-wait GPU memory | The Adreno driver takes a smaller block instead of stalling for a large one | Waits for a large block: 1400–2700 → about 30 per 7 s |
| More free memory | `vm.watermark_scale_factor` → 300 | Drawing threads waiting for memory: 30 ms/s → 0 |
| Keep the UI in RAM | System UI and Home live in a memory group that is never reclaimed or swapped | Their read-back waits: 240 ms → 25 ms per 7 s |

¹ One Galaxy Z Fold8 (SM-F971B, kernel 6.12 android16), Perfetto traces while switching between heavy apps.

Cost: the reserve keeps about 3% of RAM free; keeping the UI in RAM takes 0.6 to 1 GB from other apps.

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

## How we found them

We recorded Perfetto traces (scheduler, memory reclaim, block I/O) while reproducing the stutter, and asked of every late frame: what was the thread drawing it waiting for?

### 1. A large block of memory

```
10.3 s, before any fix: waits inside memory allocation, by block size
   4 KB     0
  32 KB   103
  64 KB   495
 128 KB   532
 256 KB   882   longest 48.8 ms, SurfaceFlinger RenderEngine
   1 MB   261
2104 of 2274 on SurfaceFlinger, System UI and the launcher
```

Never a single page: memory was not short, contiguous memory was. The sizes are the Adreno driver's pool sizes. Disassembling the phone's `msm_kgsl.ko` showed why it waits: a device-tree value, `qcom,mempool-retry-min-order`, makes it free memory on the spot and retry before it settles for a smaller block. Without that value the same driver fails fast and steps down.

[`kgsl_nowait`](kmod/kgsl_nowait.c) writes 0 to that one number, the driver's own "don't wait", and writes the old value back when unloaded. No code is patched.

```
kgsl_nowait: kgsl_pool_retry_min_order was 2, is now 0
```

After: about 30 such waits per trace.

### 2. Ordinary memory

With those gone, opening several heavy apps showed the next one:

```
6.3 s: waits inside memory allocation
   4 KB   6120   5.4 s in total, longest 130 ms
```

Now it is single pages. Apps use up the free reserve faster than the background reclaimer refills it, and then free memory themselves. With `vm.watermark_scale_factor` at 300 instead of 73 the reclaimer starts at about 3% of RAM free instead of under 1%:

```
7.3 s, similar use:
   4 KB      9   23 ms in total, none on UI threads
```

### 3. The UI's own memory, read back

What remained were thousands of short waits on storage. Each was matched to the device the thread had just asked:

```
7.3 s, threads that draw: waiting for memory to come back
             from flash   from zram
System UI      124 ms       37 ms
Launcher        75 ms        6 ms
```

The kernel had dropped code and resources of System UI and the launcher like any other app's, and they were read again from flash while the panel opened. `memory.low` did not protect them on this kernel:

```
memory.events:  low 856     reclaimed 856 times while under its protection
```

`memory.min` did:

```
7.1 s:  System UI  16 ms + 2 ms     Launcher  7 ms + 0 ms
```

The module puts both in a cgroup directly under the root, where protection needs no budget from a parent, with `memory.min=max` and `memory.swap.max=0`. They restart once so that all their memory starts inside it.

## Not fixed

- A hot phone: Samsung's thermal limits cap the CPU, and frames then wait for a CPU instead.
- Apps reloading their own memory when many are kept alive.

## Build

CI builds everything: [`build.yml`](.github/workflows/build.yml).

```sh
bash tests/run.sh dash          # module scripts against a simulated phone
./gradlew :app:assembleRelease  # the app
```

The kernel module builds against the `android16-6.12` GKI headers; see the `kmod` job.

## License

[GPL-2.0](LICENSE)
