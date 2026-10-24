# Contributing to Star Nav

Thanks for helping. Bug reports, recordings of real drives (see privacy below),
translations and code are all welcome.

## Reporting a bug

Open an issue with the app version (Config screen), phone model and Android version, dish
model and firmware, and the Probe report (Probe tab → Run probe → Copy report). The report
leaves out coordinates and identifiers unless you switch them on.

## Recordings and privacy

Session files and exports contain precise positions. Never attach a full session to a
public issue. Export only the part that shows the problem (Sessions → choose the start and
end), and prefer sending it privately.

Never commit `*.slog` files, exports or report pages built from them (the `.gitignore`
blocks the usual names).

## Building and testing

See [README.md](README.md#build-from-source). Before opening a pull request:

```bash
./gradlew :core:test :app:lintFossDebug :app:assembleFossDebug :app:assemblePlayDebug
```

- `core` stays plain Kotlin/JVM: no `android.*` imports.
- `core/src/main/proto/starlinknav/log.proto`: add fields, never renumber or reuse them.
- `core/src/main/proto/spacex/device_subset.proto`: field numbers must match the dish
  firmware's reflection; enum fields stay `int32` so values from newer firmware survive.
- Every user-visible text goes into string resources (see below).
- Keep the code style of the surrounding code (Kotlin official style).

## Translations

1. Copy `app/src/main/res/values/strings.xml` to `app/src/main/res/values-<code>/strings.xml`
   (for example `values-de`, `values-pt-rBR`) and translate the texts. Keep the format
   arguments (`%1$s`, `%1$d`) and the names unchanged; translate every `<plurals>` item your
   language needs.
2. Copy `fastlane/metadata/android/en-US/` to `fastlane/metadata/android/<locale>/` and
   translate `title.txt` (max 30 characters), `short_description.txt` (max 80) and
   `full_description.txt` (max 4000).
3. Build once (`./gradlew :app:lintFossDebug`): lint reports missing or broken strings. The
   language list for Android's per-app language settings is generated from the resource
   folders.

## License

By contributing you agree that your contribution is licensed under the
[GNU General Public License v3.0 or later](LICENSE).
