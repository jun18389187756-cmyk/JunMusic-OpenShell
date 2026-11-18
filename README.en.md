<div align="center">

### Jun Music

A music player that is **online + local**.

Real refractive liquid glass · 4 layouts · custom colors · tap-to-play · optional account

*Build the player I want to use*

</div>

---

Jun Music is a **personal project**: no team, no KPIs, not commercial. It only does what I want — spread the music on your phone across simple cards, with all data kept on-device.

> ⚠️ Please read [`REMIND.md`](REMIND.md) (license & disclaimer). The software is provided **AS IS**, **without warranty**.

## Features

- True refractive **liquid glass** (AGSL) with graceful fallback on low-end devices
- 4 layouts: vertical list / cards / stacked / poster wall
- Online library: charts, daily recommendations, global search — tap to play, nothing to download
- 8 themes × light/dark × 3 surface materials, custom primary/secondary colors
- Equalizer (presets + free curve), virtual bass, surround, bass haptics, sleep timer, listening report
- Onboarding: storybook welcome → privacy consent → notification pre-prompt → spotlight tour
- Android Auto / in-car browsing
- Optional account: cloud sync of favorites & playlists (self-hosted server)

## Build

```bash
./gradlew :app:assembleRelease
```

See [`docs/BUILD.md`](docs/BUILD.md) for details.

## License

GPL v3. See [`LICENSE`](LICENSE).
