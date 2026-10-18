<!-- Paste into the GitLab merge request description (App inclusion template). -->

New app: **ONYX**, a private peer-to-peer messenger over Tor onion services.

* [x] The app complies with the [inclusion criteria](https://f-droid.org/docs/Inclusion_Policy)
* [x] The original app author has been notified (I am the author)
* [x] All related fdroiddata and RFP issues have been referenced in this merge request: none exist
* [x] Builds with `fdroid build` and all pipelines pass (see CI)

---------------------

**About the app**
- Source: https://github.com/SirRogersJackBlood/ONYX (AGPL-3.0-only; artwork Apache-2.0)
- Messages go phone to phone over Tor v3 onion services. No phone number, no account, no server.
- End-to-end encryption with Signal's libsignal (PQXDH + Triple Ratchet).
- No Google Play Services, Firebase, analytics, ads or crash reporting. Snowflake proxy is opt-in and off by default.

**Notes for reviewers**
- Native code comes from Maven Central AARs: `org.signal:libsignal-android`, `info.guardianproject:tor-android`, `com.netzarchitekten:IPtProxy`. No other prebuilt binaries; libsignal's desktop and test libraries are excluded from packaging.
- One build per ABI (`-PonyxAbi=`), version code `10 × base + 1/2/3`.
- Reproducible builds: the recipe lists the developer's APKs (`binary:`) and the release certificate (`AllowedAPKSigningKeys`). Release builds disable VCS info and the ART baseline profile and pin NDK 30.0.16248370 (used only to strip native libraries). If reproducing fails, I'm happy to switch to F-Droid signing.
- Anti-features: none expected.
