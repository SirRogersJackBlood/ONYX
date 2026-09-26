# About

![Raven](/img/raven.png)

## Origin

About three and a half years ago, while I was at work, an attacker used my machine as a sandbox to unpack a remote-access toolkit built on Intel AMT's Local Manageability Service (LMS). I found the file hashes. After further reports that I was under attack, including one to our EDR, I followed the trail down the rabbit hole at home, quietly watching the attacker work.

While they pursued their malicious goals, I found a better use for the same building blocks: a Gunyah hypervisor, strong encryption and low-level communication ports. On paper, that was the birth of ONYX, under a different name.

## Defensive by design

ONYX protects the people using it. It never engages, traces or strikes back at anyone.

For civilians, the lawful path is **blue team**: observe your own systems, preserve evidence, report (to your security team, your EDR provider, or law enforcement), and harden. Engaging a real attacker on their infrastructure is left to authorized government and military teams. ONYX is what hardening looks like when you build it into a messenger.

## What ONYX became

ONYX turns that idea around. It uses the same kind of low-level, out-of-sight machinery for protection instead of intrusion:

- **No server to compromise.** Messages go phone to phone, onion to onion, over Tor.
- **Nothing to identify you.** No phone number, account or profile.
- **Post-quantum encryption.** Signal's own library, libsignal: PQXDH and the Triple Ratchet.
- **Keys in hardware.** The vault key lives in the phone's secure chip (StrongBox / TEE).
- **Isolation as the long-term goal.** The key vault is built so it can move into a hypervisor-protected VM (Gunyah / Android Virtualization Framework) on devices that allow it.

## A note on messages

ONYX is for people, not programs. These rules keep it from being turned into the kind of hidden command channel it was born from watching:

- **Short and plain.** 80 characters, one line: letters, numbers, emoji and basic punctuation.
- **No code.** Brackets, backticks, dollar signs, semicolons, pipes and similar symbols are removed, as are repeated symbols and invisible characters. Injection-style text arrives harmless.
- **No attachments,** now or planned.
- **Rate-limited,** with no API for other apps or scripts to send messages.

These rules are enforced both when sending and when receiving. See [Threat model](threat-model#abuse-resistance).

## Credits

- **Design, direction & QA:** SirRogersJackBlood
- **Development:** Claude (Anthropic), AI pair-programmer
- **Built on:** libsignal (Signal Messenger) · Tor, tor-android & jtorctl (The Tor Project, Guardian Project) · Snowflake via IPtProxy · ZXing
- **License:** AGPL-3.0-only · [Source on GitHub](https://github.com/SirRogersJackBlood/ONYX)

ONYX's code was written with AI assistance and tested on real devices by its designer. Independent security review is welcome: report issues privately via [GitHub Security Advisories](https://github.com/SirRogersJackBlood/ONYX/security/advisories/new).
