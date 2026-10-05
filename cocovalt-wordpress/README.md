# Cocovalt landing page for WordPress

The approved static design (`reference/index.html`) packaged as a WordPress plugin, **Cocovalt Landing**. It adds a page template called "Cocovalt Landing" that renders the page full-bleed, without the theme's header or footer, and makes the text editable in wp-admin.

**Install file:** [`dist/cocovalt-landing.zip`](dist/cocovalt-landing.zip)

## Install (about 5 minutes)

1. **Plugins → Add New → Upload Plugin**, choose `cocovalt-landing.zip`, then **Install Now → Activate**.
2. **Pages → Add New**. Give it a title (e.g. "Cocovalt"). In the right sidebar, under **Template** (block editor) or **Page Attributes → Template** (classic editor), choose **Cocovalt Landing**. **Publish**.
3. **Settings → Reading → Your homepage displays → A static page**, set **Homepage** to that page, and **Save**.

The page matches the design straight away. The default text is the approved copy.

## Editing the text

Open the page in wp-admin. Under the editor there is a **Cocovalt Landing: page text** box (it appears once the Cocovalt Landing template is selected) with:

| Field | Where it shows |
|---|---|
| Cocovalt description | Hero, left glass panel |
| Pandughar Group paragraphs 1–3 | Gold section, left column (paragraph 1 is the larger lead) |
| Button label / Button link | Maroon "Learn more" pill. An empty label hides the button |
| Browser / search title, Meta description | `<title>`, meta description and Open Graph tags |

Paragraph fields accept links, **bold** and *italic* (`<a>`, `<strong>`, `<em>`, `<br>`). Everything else is stripped. Emptying a paragraph removes it from the page. The main editor content of the page is not shown on this template.

If Yoast SEO, Rank Math, All in One SEO or SEOPress is active, the two SEO fields are hidden and the plugin outputs no title, description or OG tags, so set them in that plugin instead.

## How the brief maps to the build

The brief's Option A (a blank page template, assets loaded only on that template) is implemented as a plugin rather than a child theme. The template uses no theme header, footer or styles, so the parent theme contributes nothing. A plugin therefore produces the same output without needing to know which theme the site runs. It also survives theme updates and theme switches.

| Brief item | Implementation |
|---|---|
| Blank, full-bleed template | `templates/landing.php` outputs only `wp_head()`, `wp_body_open()`, the markup and `wp_footer()` |
| CSS/JS in their own files, only on this template | `assets/css/cocovalt.css` and `assets/js/cocovalt.js`, enqueued only when the page uses the template. The same goes for the Google Fonts URL, plus `preconnect` hints |
| No clashes with theme styles | All classes, IDs and keyframes are prefixed `cv-`, and every rule is scoped to `.cocovalt-landing`, with resets for `main`, `section`, `button`, `img`, `a`, `p`, `h2` and `svg`. On this template the theme's own stylesheets and core block/global styles are also dequeued |
| Editable text | Native meta box (no ACF dependency), stored as post meta and sanitized on save and on output |
| `<button>` logo with `aria-expanded` | Kept. It also works with Tab, Enter and Space |
| WebP, lazy-loading | WebP versions in `<picture>` with PNG/JPG fallback. Logos and lockups are lossless (identical to the PNGs), photos are q85–90. Below-the-fold images use `loading="lazy"`. The hero photo and logo are not lazy (hero photo has `fetchpriority="high"`) |
| Meta title/description, favicon, OG image | Built in (see above). Favicon and touch icon are made from the cocoa-pod mark of `cocovalt-logo-original.png`. A Site Icon set under **Appearance → Customize → Site Identity** takes precedence. To use the same icon site-wide, upload `assets/img/favicon-512.png` there. The OG image is `cocovalt-logo-original.png` |
| `prefers-reduced-motion` | All animations off. Smooth scrolling is also turned off |

## What was tested

These checks ran in a local WordPress install: a **6.7-alpha nightly** build on SQLite with PHP 8.3, which was the version available in this environment. Rendering was checked in **headless Chromium only**.

- **Pixel comparison of the WordPress page against `reference/index.html`.** It ran at 1440, 1280, 1024, 768 and 390px, with the panels closed and opened. Page heights match exactly, and at most 290 pixels per full-page screenshot differ (under 0.015%). The differing pixels are WebP compression noise in the photos.
- **Themes.** Same result with Twenty Twenty-Four (block theme). Same result with a deliberately hostile classic theme that restyles `button`, `img`, `a`, `p`, `h2`, `section`, `svg`, `.hero` and `.btn`. That theme was tested with the stylesheet isolation both on and off.
- **Editor box.** It shows only when the template is selected, saves from the block editor, and the live page updates. Script tags and `onerror` attributes are stripped.
- **Behaviour.** Exactly one `<title>` with block and classic themes. Keyboard toggling and the `aria-expanded` and hint text updates work. Reduced motion works, and so does the Scroll link. Other pages keep the theme's normal styling.

**Not tested:**
- Safari (including iOS) and Firefox, and real devices
- a production WordPress release older than 6.7, or PHP 7.4
- real Yoast, Rank Math or Elementor installs (only the detection logic was exercised)

Please check those on the live site, as the brief asks.

## Open items for the owner

1. **"Learn more" link.** It still points to `#`. Set the Pandughar Group URL in the **Button link** field.
2. **Header, footer or contact path.** The design has none. Since the site is B2B, an enquiry route will probably be needed.
3. **Old Cocovalt logo in the group grid.** It shows the old version. Replace `cocovalt-landing/assets/img/pandughar-concerns-grid.jpg` and `.webp` if an updated grid exists, keeping the file names.
4. **Hero photo resolution.** It is 1344px wide (AI-generated). A higher-resolution version would look sharper on large screens. Replace `hero-bg.jpg` and `hero-bg.webp`, keeping the 1344:752 aspect ratio or updating `aspect-ratio` in the CSS.

## For developers

```
cocovalt-wordpress/
├── cocovalt-landing/            the plugin (this folder is what goes in the zip)
│   ├── cocovalt-landing.php     template registration, asset loading, head tags
│   ├── includes/content.php     field definitions, defaults, sanitizing, getters
│   ├── includes/admin.php       page-editor meta box and saving
│   ├── templates/landing.php    the page markup
│   └── assets/                  css/, js/, img/ (originals + WebP + icons)
├── dist/cocovalt-landing.zip    upload-ready build
├── build-zip.sh                 rebuilds the zip
└── reference/                   the original static design and brief, unchanged
```

After changing anything in `cocovalt-landing/`, run `./build-zip.sh` and bump `Version` and `COCOVALT_LANDING_VERSION` in `cocovalt-landing.php` so browsers fetch the new CSS and JS.

Filters:

- `cocovalt_landing_isolate_theme_styles` (default `true`). Return `false` to keep the theme's stylesheets on this template.
- `cocovalt_landing_seo_plugin_active`. Override the SEO-plugin detection.
- `cocovalt_landing_fields`. Change field labels and defaults.
