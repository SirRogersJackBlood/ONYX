<p align="center">
  <img src="site/public/img/raven.png" alt="Raven" width="140">
</p>

# About ONYX

## Origin

About three and a half years ago, while I was at work, an attacker used my machine as a sandbox to unpack a remote-access toolkit built on Intel AMT's Local Manageability Service (LMS). I found the file hashes. After further reports that I was under attack, including one to our EDR, I followed the trail down the rabbit hole at home, quietly watching the attacker work.

While they pursued their malicious goals, I found a better use for the same building blocks: a Gunyah hypervisor, strong encryption and low-level communication ports. On paper, that was the birth of ONYX, under a different name.

## What ONYX became

ONYX turns that idea around. It uses the same kind of low-level, out-of-sight machinery for protection instead of intrusion:

- **No server to compromise.** Messages go phone to phone, onion to onion, over Tor.
- **Nothing to identify you.** No phone number, account or profile.
- **Post-quantum encryption.** Signal's own library, libsignal: PQXDH and the Triple Ratchet.
- **Keys in hardware.** The vault key lives in the phone's secure chip (StrongBox / TEE).
- **Isolation as the long-term goal.** The key vault is built so it can move into a hypervisor-protected VM (Gunyah / Android Virtualization Framework) on devices that allow it.

## A note on messages

ONYX is for people, not programs. The rules below keep it from being turned into the kind of hidden command channel it was born from watching:

- **Short and plain.** 80 characters, one line: letters, numbers, emoji and basic punctuation.
- **No code.** Brackets, backticks, `$`, `;`, pipes and similar symbols are removed, as are repeated symbols and invisible characters. Injection-style text arrives harmless.
- **No attachments,** now or planned.
- **Rate-limited,** with no API for other apps or scripts to send messages.

These rules are enforced both when sending and when receiving, and they are covered by unit tests.

## Credits

- **Design, direction & QA:** SirRogersJackBlood
- **Development:** Claude (Anthropic), AI pair-programmer
- **Built on:** libsignal (Signal Messenger) · Tor, tor-android & jtorctl (The Tor Project, Guardian Project) · Snowflake via IPtProxy · ZXing
- **License:** AGPL-3.0-only
- **Source:** https://github.com/SirRogersJackBlood/ONYX · **Website:** https://0nyx.up.railway.app

ONYX's code was written with AI assistance and tested on real devices by its designer. Independent security review is welcome: report issues privately via [GitHub Security Advisories](https://github.com/SirRogersJackBlood/ONYX/security/advisories/new). See [SECURITY.md](SECURITY.md).
