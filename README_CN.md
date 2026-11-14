# One UI Dejank

[English](README.md) | **中文**

针对三星 One UI（骁龙机型）动画卡顿的三个内存侧修复。
一个 Root 模块加一个 App，每个修复有独立开关。

| 修复 | 改了什么 | 实测¹ |
|---|---|---|
| No-wait GPU memory（显卡内存不等待） | Adreno 驱动拿不到大块内存时直接用小块，不再原地等 | 等大块的次数：每 7 秒 1400–2700 次 → 约 30 次 |
| More free memory（更多空闲内存） | `vm.watermark_scale_factor` → 300 | 绘制线程等内存：30 ms/s → 0 |
| Keep the UI in RAM（界面常驻内存） | 系统界面和桌面放进不回收、不压缩的内存组 | 两者等读回：每 7 秒 240 ms → 25 ms |

¹ 一台 Galaxy Z Fold8（SM-F971B，内核 6.12 android16），在多个大型应用之间切换时抓的 Perfetto trace。

代价：空闲内存水位会多留约 3% 的内存；界面常驻会占用 0.6–1 GB，不再让给其他应用。

## 要求

- 已 Root，并有模块管理器：KernelSU、Magisk 或 APatch。
- 显卡修复需要骁龙机型，Adreno 驱动里要有那个重试设置，内核为 GKI 系列（`android12-5.10` 到 `android16-6.12`）。只有 `android16-6.12` 在真机上跑过。
- 目前只在上面那台手机上测过。设备不支持的修复会显示为不可用，不会做任何事。

## 安装

1. 从 [Releases](https://github.com/arcatva/oneui-dejank/releases) 下载 zip，在 Root 管理器里刷入，App 会一并装上。
2. 打开 **Dejank**，授予 Root，点 **Apply**。不需要重启。

重启后模块会自动应用已打开的修复。

## 撤销

关掉对应开关，或者禁用模块后重启。不会写入任何系统分区。

如果应用修复的过程中手机意外关机，下次开机不会应用任何修复，直到你点 **Resume**。

## 我们是怎么找到的

复现卡顿的同时抓 Perfetto trace（调度、内存回收、块设备 I/O），然后对每一个迟到的帧问同一个问题：画这一帧的线程当时在等什么？

### 1. 等一大块内存

```
10.3 秒，修复前：内存分配里的等待，按块大小
   4 KB     0
  32 KB   103
  64 KB   495
 128 KB   532
 256 KB   882   最长 48.8 ms，SurfaceFlinger RenderEngine
   1 MB   261
2274 次里有 2104 次发生在 SurfaceFlinger、系统界面和桌面上
```

单页一次都没有：缺的不是内存，是连续的内存。这些大小正是 Adreno 驱动内存池的档位。反汇编手机上的 `msm_kgsl.ko` 找到了它等待的原因：设备树里的 `qcom,mempool-retry-min-order` 让驱动在退而求其次之前，先原地腾内存并重试。没有这个值时，同一个驱动会立刻失败并降一档。

[`kgsl_nowait`](kmod/kgsl_nowait.c) 只把这一个数字写成 0，也就是驱动自己的"不等"，卸载时写回原值。不修改任何代码。

```
kgsl_nowait: kgsl_pool_retry_min_order was 2, is now 0
```

之后：每段 trace 约 30 次。

### 2. 等普通内存

上面的等待消失后，连开几个大型应用，露出了下一个：

```
6.3 秒：内存分配里的等待
   4 KB   6120   共 5.4 秒，最长 130 ms
```

这次是单页。应用消耗空闲内存的速度超过后台回收补充的速度，只好自己动手腾内存。把 `vm.watermark_scale_factor` 从 73 调到 300 后，后台回收在空闲约 3% 时就开始，而不是不到 1%：

```
7.3 秒，类似的操作：
   4 KB      9   共 23 ms，界面线程上一次都没有
```

### 3. 界面自己的内存被读回

剩下的是几千次很短的存储等待。把每次等待对应到线程刚访问的设备：

```
7.3 秒，绘制线程：等内存读回来
             来自闪存     来自 zram
系统界面       124 ms       37 ms
桌面            75 ms        6 ms
```

内核像对待普通应用一样丢掉了系统界面和桌面的代码与资源，下拉面板时再从闪存重读。在这个内核上 `memory.low` 护不住它们：

```
memory.events:  low 856     在保护线以下仍被回收了 856 次
```

`memory.min` 可以：

```
7.1 秒：  系统界面  16 ms + 2 ms     桌面  7 ms + 0 ms
```

模块把两者放进直接挂在根下的 cgroup（这样保护不需要上层给预算），设 `memory.min=max` 和 `memory.swap.max=0`。它们会重启一次，让全部内存都从组内开始分配。

## 没有解决的

- 手机发热：三星的温控会限制 CPU 频率，这时帧等的是 CPU。
- 后台留的应用太多时，应用重新加载自己的内存。

## 构建

全部由 CI 构建：[`build.yml`](.github/workflows/build.yml)。

```sh
bash tests/run.sh dash          # 在模拟的手机上测试模块脚本
./gradlew :app:assembleRelease  # App
```

内核模块为每个 GKI 内核系列各构建一份，见 `kmod` 任务。

## 许可证

[GPL-2.0](LICENSE)
