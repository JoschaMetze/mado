# "Digital Picture Frame 159K" 15.6" Frameo frame (RK3326, Android 8.1, 64 GB)

Record of one unit, started 2026-10-14, host macOS 15.6 on Apple silicon. Observations are kept
separate from guesses. Unique identifiers (serial, MAC, IP) are left out.

Related: [FRAME156](pastigio-frame156.md), [FRAME157](pastigio-frame157.md). Same SoC and
partition names, but a different partition layout and twice the flash, so do not reuse their
sector offsets.

## Observations

### Identity (Loader mode, read-only queries)

| | |
| --- | --- |
| About model (Frameo Settings -> About) | `Digital Picture Frame 159K` |
| Chip (`rkdeveloptool rci`) | `36 32 33 33` = "6233", i.e. RK3326 |
| Loader USB ID | `2207:330d`, listed by `rkdeveloptool ld` as `Loader` |
| Flash (`rfi`) | Samsung eMMC, 122,142,720 sectors, 59,640 MB (about 62.5 GB; the 156/157 have 31 GB) |
| Loader capability (`rcb`) | `15 02 ...`: Direct LBA, first-4M access, read com log, read secure mode |
| Firmware (from `system/build.prop` in the backup) | `RK3326_8.1_AY_FRAMEO_202605131837`, built 2026-05-13; fingerprint `rockchip/rk3326_evb/rk3326_evb:8.1.0/OC/183608:user/release-keys`; `ro.product.brand=Digital Picture Frame`, `ro.product.model=159K`, `ro.product.device=159K`; Android 8.1 API 27, security patch 2020-11-05. Not the DP105F family of the 156/157. |

### Partition table (GPT, 512-byte sectors, from `ppt`)

| Partition | Start (hex) | Start | Size | Note vs FRAME156 |
| --- | --- | --- | --- | --- |
| uboot | 0x4000 | 16384 | 8192 | same |
| trust | 0x6000 | 24576 | 8192 | same |
| misc | 0x8000 | 32768 | 8192 | same |
| resource | 0xA000 | 40960 | 32768 | same |
| kernel | 0x12000 | 73728 | 65536 | same |
| boot | 0x22000 | 139264 | 131072 | **larger** (156: 77824) |
| recovery | 0x42000 | 270336 | 262144 | **larger** (156: 143360), start shifted |
| backup | 0x82000 | 532480 | 229376 | start shifted |
| security | 0xBA000 | 761856 | 8192 | start shifted |
| cache | 0xBC000 | 770048 | 786432 | start shifted |
| system | 0x17C000 | 1556480 | 3145728 | start shifted |
| metadata | 0x47C000 | 4702208 | 32768 | start shifted |
| vendor | 0x484000 | 4734976 | 786432 | start shifted |
| oem | 0x544000 | 5521408 | 262144 | start shifted |
| frp | 0x584000 | 5783552 | 1024 | start shifted |
| userdata | 0x584400 | 5784576 | 116358144 (to end) | start shifted, about 55.5 GiB |

### Hardware (read from the running system, 2026-10-14)

| | |
| --- | --- |
| CPU | RK3326: 4x Arm Cortex-A35 (implementer 0x41, part 0xd04, ARMv8-A, NEON/AES/SHA), all 4 online |
| CPU frequencies | 408, 600, 816, 1008, 1200, 1248, 1296, 1416, 1512 MHz; governor `interactive`, limits 408-1512 MHz (stock) |
| GPU | Arm Mali-G31 MP1 (1 core), OpenGL ES 3.2, 200-520 MHz |
| RAM | 976,860 kB total (1 GB); about 470 MB available with Frameo in front; zram swap 482 MB |
| Memory use at idle | Frameo about 200 MB PSS, system_server 77 MB, zygote 57 MB, settings 28 MB, WebView zygote 25 MB |
| Storage | eMMC 64 GB: `/data` 54 GB (0.3 GB used), `/system` 1.4 GB (78% used), `/cache` 356 MB |
| Thermal | SoC and GPU zones; 48 C at idle 2 min after boot |
| Userspace | `ro.product.cpu.abilist=arm64-v8a,armeabi-v7a,armeabi` (64-bit zygote present, unlike the 156/157 reports) |

### USB behaviour under Android

- The frame has a single USB-C port that also carries its power (powered from the Mac during
  these tests). Unplugging it restarts the frame.
- With Frameo **Settings -> About -> Beta Program -> ADB Access** on, the Mac enumerates
  **no USB device at all** (not even an unconfigured one). Toggling ADB Access off/on and a cold
  reboot changed nothing. No "Transfer from computer" entry was found in Settings.
- Loader mode: unplug, hold the power button while reconnecting the cable. The same cable and
  Mac port then enumerate `2207:330d` and `rkdeveloptool ld` responds.
- Network: the frame's Wi-Fi address answers TCP but has no open port in 1-10000; `adb connect
  <ip>:5555` is refused. No ADB mDNS service.

### Stock Loader read limit (2026-10-14, read-only)

`rkdeveloptool rl` of 64 sectors each: sectors 0-63 returned real data (`EFI PART` at sector 1,
first GPT entry `uboot`). The start of `system` (sector 1,556,480) and of `userdata`
(sector 5,784,576) returned 32,768 bytes of pure `0xCC` each, "100%" reported. Same 32 MiB
ceiling as the [FRAME157](pastigio-frame157.md#reader-needed-for-the-full-backup): a stock-Loader
backup of anything past `kernel` is not possible on this unit.

### RAM reader route (2026-10-14, owner approved)

Followed the [FRAME157 record](pastigio-frame157.md#reader-needed-for-the-full-backup) with the
same pinned rkbin commit `89047911f4a630293bb11d4375c0245e138c95d6`; all three official binaries
matched the SHA-256 values in that record, and the 4-byte patch at `0x908c` of
`rk3326_usbplug_v1.36.bin` reproduced the documented patched hash
`3f27fc0e248d8f1a96c1b5356449b333feafb16caa2b854e5354083cf8649090`.

1. `rkdeveloptool rd 3`: `Reset Device OK`, `ld` then reports `Maskrom`, still `2207:330d`. No new
   macOS accessory prompt was needed on this host.
2. `xrock maskrom rk3326_ddr_333MHz_v2.06.bin <patched usbplug>` (RC4 on, xrock default): exit 0.
   USB product string became `USB-MSC`; `ld` still says `Maskrom`; `rcb` changed from `15 02` to
   `27 02` (adds Vendor Storage and Read IDB Config), as on the 157.
3. 64-sector reads then returned real data everywhere: `boot` start = Android boot image,
   `system` and `userdata` starts = ext4 superblocks, last 34 sectors = backup GPT with `EFI PART`
   in the final sector. 0xCC bytes only at natural frequency.

Nothing was written to flash in these steps.

### Full eMMC backup (2026-10-14, verified)

`rkdeveloptool rl 0 122142720` through the RAM reader, 64 minutes at about 16 MB/s.

- Size 62,537,072,640 bytes = 122,142,720 x 512: exact.
- No 1 MiB block consists entirely of `0xCC` (59,640 blocks scanned).
- Primary GPT header and entry CRC32 valid; alternate header at the last sector, `EFI PART` present.
- Independent device re-reads matched the image byte for byte in 19 ranges: `gpt`, `uboot`, `trust`,
  `misc`, `resource`, `kernel`, `boot`, `recovery`, `security`, first 32 MiB and a middle 8 MiB of
  `system`, `metadata`, first 8 MiB of `vendor`, `oem`, `frp`, first 32 MiB plus two 8 MiB samples of
  `userdata`, and the final 34 sectors.
- gzip (pigz -6) readback: decompressed stream SHA-256 equals the raw image hash; 1,029,643,209 bytes compressed.
- Android was not running during the capture (MaskROM), so this is a consistent snapshot.
- `mmcblk boot0/boot1` and `rpmb` are not addressable through the reader and were not captured.

System apps of interest (`/system/priv-app`): `newFrameo`, `FotaUpdate`, `DeviceTest`, `webview`,
`PhotoTable`. `userdata` is plain ext4 (no FBE/FDE markers: readable names, no `/unencrypted`),
`/data/misc/adb` exists, is empty, uid 1000 gid 2000 mode 02750, label `u:object_r:adb_keys_file:s0`.
Persistent properties are per-file under `/data/property` (Android 8.1 style).

### Host notes

- Homebrew `android-platform-tools` binaries are quarantined (Gatekeeper rejects them as
  "not an app" although signed by Google LLC); `xattr -dr com.apple.quarantine` on the
  platform-tools folder fixes it.
- Command Line Tools 16.4 on this Mac has an almost empty `usr/include/c++/v1`; building
  rkdeveloptool needed `-isystem "$(xcrun --show-sdk-path)/usr/include/c++/v1"` in addition to
  the research-log build line.

### Firmware facts relevant to ADB (from the backup, read-only)

- `prop.default`: `ro.secure=1`, `ro.adb.secure=1`, `ro.debuggable=0`, so ADB needs a host key in
  `/data/misc/adb/adb_keys`. No `adb_keys` existed.
- `userdata`: `persist.sys.usb.config` = `adb`; `settings_global.xml` has `adb_enabled=1`. So the
  Frameo toggle does enable adbd; the USB side just never enumerates on the host.
- Boot ramdisk `init.rk30board.rc` has a vendor hook: `on property:persist.internet.adb.enable=1`
  -> `setprop service.adb.tcp.port 5555; restart adbd` (and `=0` turns it off). The `adbd` binary
  also contains `persist.adb.tcp.port` and `service.adb.tcp.port`, so either property gives network
  ADB without USB.
- USB gadget is configfs (`init.rk30board.usb.rc`, controller `ff300000.usb`, `idVendor 0x2207`,
  `idProduct 0x0006` for `adb`). Device tree: `usb@ff300000` is `snps,dwc2` with `dr_mode = "otg"`,
  both PHY ports `okay`; an EHCI/OHCI host pair at `ff340000`/`ff350000`. `init.rk30board.rc`
  exposes `/sys/bus/platform/drivers/usb20_otg/force_usb_mode`.
- Vendor apps: `net.frameo.frame` (priv-app `newFrameo`) and `com.adups.fota` (priv-app
  `FotaUpdate`, an OTA updater to disable before relying on the frame).
- `fstab.rk30board`: `userdata` is ext4 with `encryptable=/metadata/key_file` (not force-encrypted);
  the filesystem is plain.

### Network ADB via a userdata edit (2026-10-14, owner approved, verified)

Goal: ADB without USB device mode. Done by adding three files to `userdata` offline and writing
only the changed sectors.

1. APFS clone of the verified image (`cp -c`); `debugfs -w` with `?offset=<userdata byte offset>`:
   - `/misc/adb/adb_keys` = the host's `~/.android/adbkey.pub` plus newline, uid 1000, gid 2000,
     mode 0640, `u:object_r:adb_keys_file:s0`
   - `/property/persist.internet.adb.enable` = `1` and `/property/persist.adb.tcp.port` = `5555`,
     uid 0, gid 0, mode 0600, `u:object_r:property_data_file:s0`
   `e2fsck -fn` on the edited clone: clean. (Userdata was clean with an empty journal before.)
2. Sector diff of clone vs image: **15 ranges, 16 sectors, 8,192 bytes**, all in `userdata`:
   superblock, 3 group-descriptor sectors, 3 bitmaps (groups 0, 64, 80), 3 inode-table sectors,
   2 directory blocks (`/misc/adb`, `/property`), 3 data blocks. Offsets are specific to this unit.
3. Live precheck: all 15 ranges still equalled the backup. Written with the patched RAM reader
   (`rkdeveloptool wl`) in the order data, inodes, directories, bitmaps, descriptors, superblock;
   every range read back equal to the new bytes. **Writes past 32 MiB work through the patched
   helper**, not only reads. 40 MiB at the partition start and 32 MiB around each touched group
   re-read equal to the edited clone.
4. `rkdeveloptool rd 0`: Frameo booted normally; port 5555 open after about 30 s;
   `adb connect <ip>:5555` -> `device` (no `unauthorized`, no popup needed).

Checks after boot: `init.svc.adbd=running`, `service.adb.tcp.port=5555`, `sys.usb.config=adb`,
`sys.usb.ffs.ready=1`; `adb shell` is uid 2000; `su -c id` -> `uid=0(root)` with no prompt
(`/system/xbin/su`, 75 KB); `getenforce` -> `Permissive`; the three files show the intended owner,
mode and label. Frameo is `net.frameo.frame` versionName=v1.32.15 (updated copy under `/data/app`); OTA updater is
`com.adups.fota` (`/system/app/FotaUpdate`). Launcher packages: none found.
WebView `com.android.webview` 113.0.5672.136. Display 1920x1080 at 160 dpi.

Undo: write the saved original bytes of the 15 ranges back from Loader (same tool, reverse
order), or from a root shell delete the three files. `persist.internet.adb.enable=0` turns the
TCP port off through the firmware's own hook.

## Guesses (unverified)

- The dwc2 OTG controller settles in host role with the USB-A-to-C cable (no ID/CC signalling on
  the frame's USB-C), so adbd runs but no gadget is ever exposed. Loader mode works because the
  boot ROM forces device mode. `force_usb_mode` in sysfs might switch it once a shell exists.
- Same DP105-style firmware family as the 156/157 (not confirmed; firmware string not read yet).

## Status against the README phase map

| Phase | State |
| --- | --- |
| 1 Identify | Done (Loader queries). Firmware string pending. |
| 2 ADB | Done over Wi-Fi (port 5555) after the userdata edit; USB still does not enumerate. |
| 3 Loader mode | Done, reachable with the power button. |
| 4 Back up | Done and verified (see Full eMMC backup). Restore untested. |
| 5 Root | Shipped `/system/xbin/su` works, no prompt. |
| 6 Make it useful | Mado frame app (our copy in `frame-app/`, scaled stage for 1920x1080) installed 2026-10-15 over network ADB and connected to the hub on the VM; Frameo still the home app. |
| 7 Networking | Onboard Wi-Fi works. |
| 8 Vendor updates | `com.adups.fota` removed for user 0 on 2026-10-14 (see Changes). `android.rockchip.update.service` (RKUpdateService 1.8.1, INTERNET, boot and connectivity receivers) still enabled. |
| 9 Security hand-off | Pending. |

## Changes made to the frame

| Change | How | Undo |
| --- | --- | --- |
| Host ADB key added | `/data/misc/adb/adb_keys` written offline into `userdata` (15 sector ranges, see above) | delete the file from a root shell, or write the saved original sectors back from Loader |
| Network ADB on | `/data/property/persist.internet.adb.enable=1` and `persist.adb.tcp.port=5555`, same write | `setprop persist.internet.adb.enable 0` from a shell, or delete both files |
| OTA updater off | `com.adups.fota` is a PERSISTENT system app: `pm disable-user` and even root `pm disable` leave it running and AMS restarts it after `kill`/reboot. What worked: `su -c "pm uninstall -k --user 0 com.adups.fota"` (APK stays in `/system/app/FotaUpdate`, verified gone after reboot). | `cmd package install-existing --user 0 com.adups.fota` (verified to exist on this build; `pm install-existing` is not), then reboot |

Nothing under `/system`, `/vendor`, `/oem`, `boot`, `kernel` or `uboot` was written.

### Security notes for the owner

- Network ADB on port 5555 is now open on the Wi-Fi address and any host with the key in
  `adb_keys` gets a shell; `su` then gives root with no prompt. Keep the frame on an isolated or
  guest network.
- Android 8.1, security patch 2020-11, SELinux permissive, `com.adups.fota` can install updates
  once online.
