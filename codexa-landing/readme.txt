=== Codexa Landing ===
Contributors: pandughar
Tags: landing page, coming soon, full width, template
Requires at least: 5.8
Tested up to: 7.1
Requires PHP: 7.4
Stable tag: 1.0.0
License: GPLv2 or later
License URI: https://www.gnu.org/licenses/gpl-2.0.html

The Codexa coming-soon landing page as a full-width page template and a [codexa_landing] shortcode. Works with any theme and doesn't need Elementor.

== Description ==

Codexa Landing outputs the Codexa landing page, a concern of Pandughar Group:

1. Hero with drifting glows, rings, ripples, orbiting nodes, a pulsing orb and mouse parallax.
2. About section with the group copy, 15 brand logos and a magnetic "Learn more" button.
3. A dark "Coming soon" section with scroll-driven liquid-glass panes and the Codexa "O" progress loader.

All motion stops when the visitor's system has "Reduce motion" turned on.

The stylesheet and script load only on pages that show the landing page. All styles are scoped under `.cdx-page`. There are no dependencies, so it doesn't load jQuery.

== Installation ==

1. Go to Plugins → Add New → Upload Plugin, choose `codexa-landing.zip`, then click Install Now and Activate.
2. A notice offers to create a "Codexa" page that uses the landing template. You can also make it the homepage. Nothing changes until you click a button. The same buttons are under Settings → Codexa Landing.
3. To set it up by hand, edit any page and pick **Template → Codexa Landing (full width)**. To make it the homepage, go to Settings → Reading → "A static page" and choose that page.
4. Go to Settings → Codexa Landing and enter the "Learn more" URL. The button stays hidden until a URL is set.

= Full-width template or shortcode? =

The **Codexa Landing (full width)** page template is recommended. It prints the landing page edge to edge, with none of the theme's header, footer or containers. `wp_head()` and `wp_footer()` still run, so plugins such as analytics and SEO keep working.

The `[codexa_landing]` shortcode renders the same markup inside the theme's layout, for example in a block editor Shortcode block or an Elementor Shortcode widget. Because it sits inside the theme's content column, the hero may not run edge to edge.

== Settings ==

Settings → Codexa Landing:

* **"Learn more" URL.** Leave it empty to hide the button.
* **Open in a new tab.**
* **Copyright line.** Default `© {year} Codexa`; `{year}` is replaced with the current year.
* **Hero pulse speed.** In seconds, default 4. This sets the CSS variable `--cdx-pulse`.
* **Liquid strength.** From 0 to 2, default 1. Controls how strongly the glass panes move.
* **Font source.** Google Fonts, or self-hosted copies in `assets/fonts/` so the browser makes no requests to Google.
* **Page title and meta description.** Used on the full-width template. If Yoast SEO, Rank Math, All in One SEO or SEOPress is active, the meta description is left to that plugin.

== Frequently Asked Questions ==

= The template doesn't appear in the Template dropdown =

Some block themes list only their own block templates in the editor. Use the "Create the Codexa page" button under Settings → Codexa Landing. It assigns the template directly.

= Does it need Elementor? =

No. It works with any theme. With Elementor you can use the full-width template, or the Shortcode widget.

== Changelog ==

= 1.0.0 =
* First release, built from the Codexa design handoff (reference build).
* On screens 760px wide or narrower, the coming-soon glass panes come to rest between the title and the loader. The Pandughar lockup is also capped so it can't overlap the Codexa logo. Wider screens match the reference exactly.
