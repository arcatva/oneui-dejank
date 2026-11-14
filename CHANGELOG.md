## v0.1.1

- No-wait GPU memory is built for every GKI kernel series, `android12-5.10` to `android16-6.12`. Only `android16-6.12` has run on a phone.
- A phone whose driver never waits shows "Not needed" instead of "No build".
- Removing the module removes the app.
- Signed with a temporary key: the next update replaces the app and asks for root again.

- 显卡修复覆盖全部 GKI 内核系列（`android12-5.10` 到 `android16-6.12`），只有 `android16-6.12` 在真机上跑过。
- 驱动本来就不等待的手机显示"Not needed"，不再显示"No build"。
- 卸载模块时一并卸载 App。
- 本版使用临时密钥签名：下次更新会替换 App，需要重新授予 Root。

## v0.1.0

First release: No-wait GPU memory, More free memory, Keep the UI in RAM.

首个版本：显卡内存不等待、更多空闲内存、界面常驻内存。
