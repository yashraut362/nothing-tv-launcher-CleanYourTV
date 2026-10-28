# Nothing-style TV launcher

A home screen and screensaver for Android TV in the Nothing OS design language.
Built for a 1 GB Xiaomi Mi TV 4A, where it runs in about 24 MB: plain Java, no ads, no
recommendations, no background services.

### Screensaver

![Screensaver](docs/screenshots/screensaver.jpg)

### Home screen

![Home screen](docs/screenshots/home-circles.jpg)

Made after [CleanYourTV](https://cleanyour.tv/): back up, disable the bloat with
`pm disable-user` (nothing uninstalled), install your own Home.

## Features

- **Rail:** a dot-matrix clock (Doto), the weekday in red, and three readouts: Input, Network
  (your Wi-Fi name) and System. The focused readout inverts and carries the red dot.
- **Apps:** circles, four to a row, pinned apps first. Every app is a white line glyph from
  [Lawnicons](https://github.com/LawnchairLauncher/lawnicons), so all of them match. Focus
  inverts the circle.
- **Inputs:** HDMI 1 to 3, AV and Live TV expand in place inside the rail. A red dot marks an
  input whose device is on.
- **Screensaver:** a fixed clock with the day as 24 dots (the current hour pulses red), and the
  weather now plus seven days.
- **Look:** Nothing's near-black (`#040404`), one red (`#D71921`), Doto, Geist Light and Geist
  Mono Light, 1 dp hairlines. No glow, blur or gradients.

| Rail focused                                       | Input list                                         |
| -------------------------------------------------- | -------------------------------------------------- |
| ![Rail](docs/screenshots/rail-focused-circles.jpg) | ![Inputs](docs/screenshots/input-list-circles.jpg) |

The research and design behind it are in [design/proposal.md](design/proposal.md).

## Install

You need network ADB on the TV (Settings → Device Preferences → About, press OK on Build seven
times, then Developer options → USB debugging), JDK 17 and the Android SDK (platform 36).

```bash
TV_HOST=192.168.1.50 ./build-install.sh
```

This builds the launcher, installs it and sets it as Home. If the stock launcher keeps
winning, disable it (reversible):

```bash
TV_HOST=192.168.1.50 tools/disable.sh com.google.android.tvlauncher com.google.android.tvrecommendations
```

To use the screensaver, pick "Nothing clock" in Settings → Device Preferences → Screen saver.

## The remote

- **D-pad** moves one step. Left from the first column enters the rail.
- **OK** opens an app, expands the input list, or opens settings.
- **Menu** opens the input list from anywhere. **1 to 9** open the app in that position.
- **Back** or **Home** returns to the first app. Coming back from an app keeps your place.

## Customise

Edit `launcher/app/src/main/res/values/config.xml`, then run `./build-install.sh` again:

- `dock_apps`: pinned apps, in order. `hidden_apps`: apps never shown.
- `tile_shape`: `circle` (default) or `banner` for the apps' own 16:9 banners.
- `weather_latitude`, `weather_longitude`, `weather_place`: pin the weather to a place.
  `weather_unit`: `celsius` or `fahrenheit`.

Colours, sizes and timings are tokens in `colors.xml`, `dimens.xml` and `Motion.java`.

## Debloat and undo

`debloat-xiaomi-mi-tv-4a.txt` lists what I disabled on a Mi TV 4A: telemetry, PatchWall
content, preloaded apps, Google Assistant and Xiaomi updates. For other TVs, start from the
[CleanYourTV lists](https://github.com/Gathin23/cleanyourtv/tree/main/examples).

To undo everything, run:

```bash
TV_HOST=192.168.1.50 ./restore.sh
```

It re-enables every disabled package, resets Private DNS and the screensaver, and uninstalls
the launcher.

When you're done, switch ADB off: Developer options → Revoke USB debugging authorisations,
turn USB debugging off, reboot.

## Network use

Home never goes online. Only the screensaver does, for the weather:

- [Open-Meteo](https://open-meteo.com) at most every 30 minutes, while the screensaver is on.
- ipapi.co once a week, to find your city. Set your coordinates in `config.xml` to skip this.

No analytics, no accounts.

## App icons

About 10,000 Lawnicons glyphs cover some 14,000 apps. An app with no glyph gets its first
letter in the same style. `tools/lawnicons-tv.txt` adds TV-specific packages. To refresh the
glyphs from a newer Lawnicons:

```bash
git clone --depth 1 https://github.com/LawnchairLauncher/lawnicons.git /tmp/lawnicons && tools/lawnicons.py /tmp/lawnicons launcher/app/src/main/assets
```

## Known limits

- Tested on one TV only: Xiaomi Mi TV 4A, Android TV 9, 1280x720.
- The red input dot is untested with a real HDMI device.
- On Android 10 and later, Network shows "Wi-Fi" instead of the network's name.
- No touch input, English only.

## Licence

MIT, see [LICENSE](LICENSE). App glyphs: [Lawnicons](https://github.com/LawnchairLauncher/lawnicons)
(Apache-2.0). Fonts: [Doto](https://github.com/oliverlalan/Doto) and
[Geist](https://github.com/vercel/geist-font) (SIL OFL), licences in
[launcher/font-licenses](launcher/font-licenses). The weather icons were drawn for this project.

Nothing is a trademark of Nothing Technology Limited. This fan project is not made, endorsed or
supported by Nothing.

open for any new contribution !
