# Bulk Studio: Claude + Higgsfield bulk generator (Chrome & Opera)

Type one idea → Claude writes up to 500 different prompts → you review/edit them →
Higgsfield generates the images or videos → files are saved straight into a folder on your PC.

- **Claude** only writes the prompts. It never makes images/videos.
- **Higgsfield** makes the images/videos.
- Nothing runs until you press **Start** when you open the extension.

---

## Install on Windows

You do **not** need to install Node or build anything. The ready-to-load extension is the
`extension` folder.

1. Download this repository (green **Code** button → **Download ZIP**) and unzip it somewhere
   permanent, e.g. `C:\Tools\bulk-gen-extension`. Don't delete the folder afterwards: the browser
   loads the extension from it.

### Chrome
1. Go to `chrome://extensions`
2. Turn on **Developer mode** (top right).
3. Click **Load unpacked** and choose the `extension` folder (inside `bulk-gen-extension`).
4. Pin it: puzzle-piece icon in the toolbar → pin **Bulk Studio**.

### Opera
1. Go to `opera://extensions`
2. Turn on **Developer mode** (top right).
3. Click **Load unpacked** and choose the same `extension` folder.

**Updating later:** replace the folder with the new version, then click the ↻ (reload) button on
the extension's card in `chrome://extensions` / `opera://extensions`.

---

## First-time setup

1. Click the Bulk Studio icon → a tab opens → press **Start**.
2. Go to **Settings**:
   - **Anthropic API key**: from <https://platform.claude.com/settings/keys>. Press **Test connection**.
   - **Higgsfield API key**: from the Higgsfield developer console, in the form `KEY_ID:KEY_SECRET`.
     Press **Test connection**. (The test only asks Higgsfield for an upload link. It does not start a generation.)
   - **Claude model**: Opus 5.5 (best), Sonnet 5.5, or Haiku 4.5 (cheapest).
   - **Generations at the same time**: start with **2**.
   - Press **Save settings**.
3. **Add your Higgsfield models** (Settings → Higgsfield models). See below.
4. On the **Generate** tab, click **Choose output folder** and pick a folder. The browser asks for
   permission. In Chrome, choose "Allow on every visit" if offered, so it remembers.

### Adding a Higgsfield model

Open the model's page on **docs.higgsfield.ai** and copy:

| Field | What to put | Example |
|---|---|---|
| ID | any short name | `kling-3-std` |
| Name | shown in the dropdown | `Kling 3.0 Standard` |
| Type | image or video | `video` |
| Path | the part of the endpoint URL **after** `https://api.higgsfield.ai/` | `kling-video/v3.0/std/text-to-video` |
| Options (JSON) | settings you want as dropdowns, with the values the docs allow | `{"aspect_ratio": ["16:9","9:16"], "duration": [5, 10]}` |
| Always send (JSON) | settings sent with every request | `{"resolution": "1080p"}` |
| Price | your estimate in USD per generation, for the cost estimate | `0.35` |

The example values above are only to show the format. Always copy the real ones from the docs.
One model ("Seedream v4") is pre-filled from the example in Higgsfield's official SDK readme.
Check it against the docs before relying on it.

---

## Using it

1. Choose **Image** or **Video**, then the **model**.
2. Paste your **prompt** (idea). Set **How many** (1-500, default 100).
3. Pick a **variation style** and optional **rules** (brand colours, things to avoid...).
   - Or tick **Use my lines exactly** to paste your own prompts, one per line (Claude is skipped).
4. Press **Preview prompts**. Edit, delete (✕) or rewrite (↻) any prompt.
5. Check the **estimated cost**. It's an estimate based on the price you entered.
6. Press **Generate**.

While it runs you'll see Queued / Running / Done / Failed and thumbnails as files arrive.
**Pause**, **Resume**, **Cancel** and **Retry failed** are on the batch card.

- Each failed generation is retried **2 times automatically**. After that, use **Retry failed**.
- If Higgsfield says it's busy (rate limit), the extension waits and runs fewer jobs at once, then speeds back up.
- If a file can't be downloaded, **Retry failed** re-downloads it without paying for a new generation.
- **Keep the Bulk Studio tab open while a batch runs.** If you close it or the browser restarts,
  open it again → **Start** → **Resume**. Jobs that were already at Higgsfield are picked up again, not
  paid for twice. (Higgsfield keeps finished files for a limited time. Their docs reportedly say at least 7 days. Resume well before that.)

### What gets saved

```
<your folder>\
  summer-shoes_2026-10-05\
    images\                       (or videos\)
      summer-shoes_001_red-sneaker-on-a-dune-at-golden-hour.png
      summer-shoes_002_....png
    manifest.csv
```

`manifest.csv` columns: `number, final_prompt, model, settings, status, file_name, error, time`.
It opens in Excel.

### Claude Code

Claude Code can read everything directly from your output folder. For example, ask it:
"Read `D:\Generations\summer-shoes_2026-10-05\manifest.csv` and list the failed prompts."

---

## Permissions (and why)

| Permission | Why |
|---|---|
| `storage` | Save your settings and API keys in this browser only |
| `unlimitedStorage` | Remember large batches (500 items) so they survive a restart |
| `https://api.anthropic.com/*` | Ask Claude to write prompts |
| `https://api.higgsfield.ai/*` | Start generations and check on them |
| optional: other `https://` sites | **Only asked for if needed**, when Higgsfield's file server blocks a download. You get an "Allow downloads from this site" button naming the exact site. |

No access to your browsing, tabs, or other websites.

## Privacy & safety

- Keys are stored in `chrome.storage.local` on this computer, are only sent to Anthropic/Higgsfield,
  and are never written to logs or the manifest.
- Higgsfield's guidance is to keep API keys out of browser code. You chose to accept this for personal
  use. Anything with access to your browser profile could read the key. Keep a modest balance or a
  spending limit on your Higgsfield account.

## Known limits / not verified yet

- **Higgsfield docs could not be read** while this was built. The request flow (submit → check status →
  download, error codes) comes from Higgsfield's official SDK source code. The model list, allowed
  settings, prices, and exact rate/concurrency limits must come from docs.higgsfield.ai (enter them in Settings).
- **Not yet tested against the real Higgsfield or Claude APIs**. Only against fake test servers. Start with a batch of **1**, then 5, then 100.
- Reference images (image-to-image / image-to-video) are not included yet.
- Opera: uses only features MDN lists as supported in Opera. It has not been run in Opera yet.

---

## For developers

```
src/            source (plain JavaScript, ES modules)
  lib/          claude.js, higgsfield.js, queue.js, files.js, db.js, settings.js, models.js, util.js
  ui/           studio.html / .css / .js, ticker.js
  background.js toolbar button → opens the Studio tab
extension/      built output, the folder you load in the browser
test/           unit tests + an end-to-end browser test with fake APIs
```

- `npm install` once, then `npm run build` after editing `src/`. This bundles Anthropic's official SDK with esbuild.
- `npm test` runs the unit tests.
- `node test/e2e.mjs 5 3` loads the extension in Chromium and runs a 5-item batch (concurrency 3)
  against fake Claude/Higgsfield servers.
