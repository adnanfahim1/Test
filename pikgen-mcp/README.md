# PikGen for Claude Desktop

Bulk-generate images and videos by **chatting with Claude Desktop**.

- **Claude** writes all the prompts (part of your Claude plan, no API key or API credit needed).
- **Higgsfield's official connector** generates them using your **Higgsfield plan credits**
  (the credits you see on higgsfield.ai, e.g. "Pro Plan - 592 left").
- **PikGen** (this extension) works on your PC: reads your picture folders, uploads reference
  pictures, and saves every result into your output folder with a `manifest.csv`.

## Install (Windows, one time)

1. **Download `pikgen.mcpb`** from this folder (`pikgen-mcp/pikgen.mcpb`): on GitHub, open the
   file and click the download button.
2. **Double-click `pikgen.mcpb`.** Claude Desktop opens and asks to install PikGen. Click **Install**.
   (Or: Claude Desktop → Settings → Extensions → Advanced → Install extension → choose the file.)
3. When it asks for **Folders PikGen may use**, add:
   - the folder with your pictures (for example `D:\Products`)
   - the folder where results should go (for example `D:\Generations`)

   PikGen can only touch these folders and their sub-folders.
4. Make sure the **Higgsfield** connector is on: Claude Desktop → Settings → Connectors → Higgsfield
   → Connect (sign in with your higgsfield.ai account). It is the same account that shows your credits.

## Use it

In a new Claude Desktop chat, just ask, for example:

> Using PikGen and Higgsfield: edit every picture in D:\Products\sweaters with Qwen image edit:
> clean white studio background, keep the sweater exactly the same. Save to D:\Generations.
> Start with 2 pictures so I can check.

or

> Using PikGen: make 50 different product photos of a red sneaker on sand dunes, vary angle and
> lighting, 1:1, save to D:\Generations\sneakers.

Or click the **+** button in the chat → PikGen → **bulk_generate** and fill in the form.

Claude will:
1. Pick a model, write the prompts, tell you the **credit cost**, and ask before spending.
2. Create `D:\Generations\<batch>_<date>\images\` (or `videos\`) and `manifest.csv`.
3. Upload your pictures (if any), generate 12 at a time, and save each result as it finishes.

If something stops halfway, say **"resume the PikGen batch in D:\Generations"**.
Jobs already sent to Higgsfield are picked up again, never paid twice.

## What gets saved

```
D:\Generations\
  mens-sweater_2026-10-05\
    images\
      mens-sweater_001_clean-white-studio-background.png
      ...
    manifest.csv        number, final_prompt, model, settings, status, file_name, error, time, reference_image, job_id
    pikgen-batch.json   progress (used for resuming)
```

## Tools PikGen gives Claude

| Tool | What it does |
|---|---|
| `pikgen_list_images` | Lists pictures in a folder |
| `pikgen_upload_files` | Uploads pictures to Higgsfield upload links (only `*.higgsfield.ai`) |
| `pikgen_start_batch` | Creates the batch folder + manifest |
| `pikgen_record_jobs` | Remembers Higgsfield job ids (safe resume) |
| `pikgen_save_results` | Downloads results with proper names, updates the manifest |
| `pikgen_batch_status` | Progress, what to resume or retry |
| `pikgen_list_batches` | Lists batches in an output folder |

## Safety

- PikGen only reads/writes inside the folders you allowed.
- It only uploads to Higgsfield's own upload servers.
- It never sees your Higgsfield or Claude login; generation goes through the official Higgsfield connector.
- Claude Desktop asks before using tools (you can allow PikGen permanently).

## Not tested yet on a real PC

Everything was tested with automated tests (including a real MCP connection), and the packed
extension starts correctly. Real uploads to Higgsfield could not be tested from the build machine
(its network blocks Higgsfield's upload server), so the first run with pictures is the real test.
Start with 1-2 pictures.

## For developers

`npm install`, `npm test`, then `npx mcpb pack <folder> pikgen.mcpb` (see the build steps in git history).
