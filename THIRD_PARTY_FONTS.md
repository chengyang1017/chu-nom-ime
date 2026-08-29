# Nôm font sources

The app supports multiple selectable Nôm fonts. Large optional font binaries are installed locally and are intentionally not committed to Git history.

## Bundled

- **Minh Nguyen** — serif Hán-Nôm font by TKYKmori / Han-Nom Revival Committee. SIL Open Font License.
- **Plangothic P1** — wide CJK extension fallback font. SIL Open Font License 1.1.

## Optional font pack

Run on Windows from the repository root:

```powershell
.\tools\fonts\install_optional_nom_fonts.ps1
```

This installs:

- **Gothic Nguyen Regular** — sans-serif Hán-Nôm font. Source: https://github.com/TKYKmori/Gothic-Nguyen . SIL Open Font License. The installer pins Git blob `7edfe73d9b730e3ae3422fd5d8c7bd73b8b9ac18`.
- **Nom Na Tong Regular v5.17** — reference Nôm font maintained by the Nom Foundation. Source: https://github.com/nomfoundation/font/releases/tag/v5.17 . The installer pins SHA-256 `8c1819185482f53395341cd99e806bfb57a11d5caf9cb1ab2637e0d7186290fb` for `NomNaTong-Regular.otf`.

After installing, rebuild/reinstall the Android app. The Settings screen will automatically expose every font that is present in `app/src/main/assets/fonts/`.

The selected font is tried first; missing glyphs automatically fall back to Plangothic P1 and then Minh Nguyen/system fallback.
