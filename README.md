# Codexa Landing: WordPress plugin

`codexa-landing/` is a WordPress plugin built from the Codexa design handoff. It outputs the Codexa landing page (hero, about, liquid-glass coming soon) as a full-width page template and as a `[codexa_landing]` shortcode.

**Ready-to-install zip:** [`dist/codexa-landing.zip`](dist/codexa-landing.zip)

## Install

1. Go to WordPress admin → Plugins → Add New → Upload Plugin, upload `dist/codexa-landing.zip`, then click Activate.
2. A notice offers to create a "Codexa" page that uses the **Codexa Landing (full width)** template, and optionally make it the homepage. The same buttons are under Settings → Codexa Landing.
3. In Settings → Codexa Landing, set the "Learn more" URL. The button stays hidden until a URL is set.

See [`codexa-landing/readme.txt`](codexa-landing/readme.txt) for all settings.

## Rebuild the zip

```bash
./build.sh   # writes dist/codexa-landing.zip
```

## Layout

```
codexa-landing/
├── codexa-landing.php               plugin header + bootstrap
├── includes/class-codexa-landing.php    template routing, shortcode, assets, setup notice
├── includes/class-codexa-settings.php   Settings → Codexa Landing
├── templates/page-codexa-landing.php    blank-canvas template (wp_head/wp_footer only)
├── templates/partials/landing.php       the page markup
├── assets/{css,js,img,fonts}            from the reference build; fonts are self-hosted copies (OFL)
├── uninstall.php                        removes the plugin's options (keeps pages)
└── readme.txt
```
