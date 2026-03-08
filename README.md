<div align="center">
  <h1>Kallme-Xposed</h1>
  <br>
</div>

**ReVanced LSPosed module by ChsBuffer, just for Spotify.**  
>[!IMPORTANT]  
> - This is **NOT an official ReVanced project**, do not ask the ReVanced developers for help.
---

>[!IMPORTANT]
>As of **current** testing accounts get blacklisted at about midnight, its likely thats when they do checks for the on demand trial that i force.
>HOWEVER, if youre fine with making a new account theres downloads and **NO ADS**

> [!WARNING]  
> **Development Status:** This project is currently in active development. Features are being tested to ensure account safety and stability before the full source is released.
------------

## 🔍 The Project Mission
This patch utilizes many of the same tactics as the legacy ReVanced patches—specifically client attribute spoofing and **DexKit** for high-speed reading of obfuscated libraries—but with fixes for glaring stability issues and several new qol additions.

### Research & Findings
* **Blacklists:** Spotify performs periodic checks. If the client reports "Premium" attributes while the server database sees a "Free" account, the server issues a `pushka-tokens` delete command, revoking the session token.
* **The ReVanced Approach:** Current popular patches set attributes to "Free" and "On-Demand." This prevents blacklisting but often leaves ads intact.
* **My Approach:** Auto-closing pop-up ads and, if necessary, programmatically applying a 14-day trial state to the account to maintain functionality without triggering the blacklist.
-------------



## 🛠 Development Logs

| Date | Status Update |
| :--- | :--- |
| **Mar 08, 2026** | Added track dumping that auto dumps tracks for offline playability no matter what, still low quality but good. |
| **Mar 06, 2026** | Added attribute spoofing; implemented custom lyrics patch; video ads remain the only outlier. |
| **Mar 02, 2026** | Identified ad-blocking as the primary stability fix. Canvases are currently optional/disabled. |
| **Mar 01, 2026** | Refined Protobuf and Map modification. *Note: "Something went wrong" on the homepage is fixed.* |
| **Feb 28, 2026** | Successfully achieved memory writing; discovering new offsets for free attribute forcing. |
| **Feb 25, 2026** | Confirmed server-side license validation logic and the 5-minute blacklist trigger. |
-------------

## ⚠️ Known Issues
* **Video Ads:** These are handled via a different stream and are not yet suppressed.
* **Canvases:** Functionality is currently inconsistent and may be disabled by preference.
* **Blacklists** This is the last issue and the most difficult, my eta is a week or 2 before its solved
---

### Regarding alleged “new working Spotify mods”:

Recent claims that _Nibrut, Obito, AndroForever and Shizuku_ provide functioning Spotify mods are incorrect.  
Their mod merely applies _Rootless Xposed Framework_ and _generic signature bypass patcher_ together with this module,  
e.g. Mochi Cloner, App Cloner, LSPatch, NPatch, HKP, MT Manager, NP Manager.  
However, it **does not** address or bypass the actual mechanisms responsible for detecting and blocking modified clients.    
ReVanced Xposed has nothing to do with the bypass mechanisms.  
  
These mods work for a few days until a Spotify app update is released, then Spotify blacklists users of these modded apps on old versions of the client from the server.  
  
Before ReVanced paused patches for Spotify for legal reason,  
they released a working test version that still works to this day.  
There is something you need to know in order to use it, so find it on the xManager Discord Server and read the instructions.  

## Patches

## ✨ Features (Current Build)
* **Advanced Spoofing:** Specifically designed to fix issues for devices that have been blacklisted multiple times.
* **"Who Sampled" Integration:** Deep-dive into song origins directly within the app.
* **Custom Lyrics Patch:** A beautiful, real-time lyrics UI that mimics the Apple Music aesthetic.
* **Playlist Pinning:** Enhanced organization allowing you to pin any playlist for quick access.
* **Improved Navigation:** A modified and streamlined Navbar for better UX.
* **Ad Suppression:** Successfully blocks most UI and audio ads (Video ads are currently a work-in-progress).
* **Track Dumping** When you play a song the mod auto records and saves it, it only records from spotify and is 90% of the quality. 
-------------

## 🚀 Roadmap
- [ ] Fully reverse-engineer the attribute reporting library.
- [ ] Implement video ad suppression.
- [x] **Open Source Release** (Once the bypass is verified as "Blacklist-Proof").`
---


## ⭐ Credits

[DexKit](https://luckypray.org/DexKit/en/): a high-performance dex runtime parsing library.  
[ReVanced](https://revanced.app): Continuing the legacy of Vanced at [revanced.app](https://revanced.app)  
